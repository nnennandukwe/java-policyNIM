package io.policynim.mcp.transport;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.modern.McpStdio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A flushed SDK response retires an accepted request. Boundary rejections share stdout but never retire SDK work. */
final class GroundedStdioReplies extends OutputStream {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTSTANDING = 1024;
    private final OutputStream output;
    private final Consumer<IOException> fail;
    private final int capacity;
    private final Map<Object, Boolean> pending = new HashMap<>();
    private BiConsumer<Object, Boolean> initializeReply = (id, success) -> { };
    private volatile Receipt writing;
    private boolean stopped;

    GroundedStdioReplies(OutputStream output, Consumer<IOException> fail) {
        this(output, fail, MAX_OUTSTANDING);
    }

    GroundedStdioReplies(OutputStream output, Consumer<IOException> fail, int capacity) {
        this.capacity = capacity;
        this.output = output;
        this.fail = fail;
    }

    synchronized void onInitializeReply(BiConsumer<Object, Boolean> callback) {
        initializeReply = callback;
    }

    boolean accepted(String method, Object id) {
        Object key = McpStdio.idKey(id);
        Receipt publishing = writing;
        if (publishing != null && key.equals(McpStdio.idKey(publishing.id()))) {
            // The peer can receive a line before the underlying flush returns. Wait for that
            // writer alone before checking a legitimately reused ID.
            synchronized (this) {
                return reserve(method, id, key);
            }
        }
        return reserve(method, id, key);
    }

    private boolean reserve(String method, Object id, Object key) {
        synchronized (pending) {
            if (stopped || pending.containsKey(key)) {
                // A second response with this ID would be ambiguous for the outstanding request.
                return false;
            }
            if (pending.size() < capacity) {
                pending.put(key, "initialize".equals(method));
                return true;
            }
        }
        // Continue reading cancellations and completions after refusing excess work.
        var refusal = JSON.createObjectNode().put("jsonrpc", "2.0");
        refusal.set("id", JSON.valueToTree(id));
        refusal.putObject("error").put("code", -32603)
            .put("message", "Too many outstanding stdio requests. Retry after an earlier request completes.");
        try {
            writeBoundary((refusal + "\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException closedOutput) {
            // writeBoundary has already notified the runtime to close.
        }
        return false;
    }

    void cancelled(Object id) {
        synchronized (pending) {
            pending.remove(McpStdio.idKey(id));
            pending.notifyAll();
        }
    }

    void awaitFlush(Duration timeout) throws IOException, InterruptedException {
        long allowance = timeout.toNanos();
        long started = System.nanoTime();
        synchronized (pending) {
            while (!pending.isEmpty() && !stopped) {
                long remaining = allowance - (System.nanoTime() - started);
                if (remaining <= 0) {
                    throw new IOException("MCP requests exceeded the configured drain timeout at end of stdin");
                }
                TimeUnit.NANOSECONDS.timedWait(pending, remaining);
            }
        }
    }

    void stopWaiting() {
        synchronized (pending) {
            stopped = true;
            pending.notifyAll();
        }
    }

    synchronized void writeBoundary(byte[] line) throws IOException {
        try {
            output.write(line);
            flushDestination();
        }
        catch (IOException failure) {
            fail.accept(failure);
            throw failure;
        }
    }

    @Override
    public synchronized void write(int value) throws IOException {
        try {
            output.write(value);
        }
        catch (IOException failure) {
            fail.accept(failure);
            throw failure;
        }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        try {
            // The modern SDK writes one complete line. Legacy writes the serialized message and delimiter separately.
            if (length > 1) {
                writing = receipt(bytes, offset, length);
            }
            output.write(bytes, offset, length);
            if (length > 0 && bytes[offset + length - 1] == '\n') {
                // The modern SDK writes a whole line before calling flush outside this monitor.
                flush();
            }
        }
        catch (IOException failure) {
            fail.accept(failure);
            throw failure;
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        try {
            flushDestination();
        }
        catch (IOException failure) {
            fail.accept(failure);
            throw failure;
        }
        if (writing != null) {
            Boolean initialize;
            synchronized (pending) {
                initialize = pending.remove(McpStdio.idKey(writing.id()));
                pending.notifyAll();
            }
            if (Boolean.TRUE.equals(initialize)) {
                initializeReply.accept(writing.id(), writing.success());
            }
            writing = null;
        }
    }

    private void flushDestination() throws IOException {
        output.flush();
        // System.out is a PrintStream and records write failures instead of throwing them.
        if (output instanceof java.io.PrintStream stream && stream.checkError()) {
            throw new IOException("MCP stdout closed during a response");
        }
    }

    private static Receipt receipt(byte[] bytes, int offset, int length) throws IOException {
        try (JsonParser parser = JSON.createParser(bytes, offset, length)) {
            Object id = null;
            boolean success = false;
            boolean response = false;
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    if ("id".equals(field)) {
                        id = value == JsonToken.VALUE_STRING ? parser.getText() : value.isNumeric() ? parser.getNumberValue() : null;
                    }
                    else if ("result".equals(field) || "error".equals(field)) {
                        success = "result".equals(field);
                        response = true;
                    }
                    parser.skipChildren();
                }
            }
            return response && id != null ? new Receipt(id, success) : null;
        }
    }

    private record Receipt(Object id, boolean success) { }
}
