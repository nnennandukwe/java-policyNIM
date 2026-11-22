package io.policynim.mcp.transport;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.modelcontextprotocol.modern.McpStdio;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A flushed SDK response retires an accepted request. Boundary rejections share stdout but never retire SDK work. */
final class GroundedStdioReplies extends OutputStream {
    private static final JsonFactory JSON = new JsonFactory();
    private final OutputStream output;
    private final Consumer<IOException> fail;
    private final Map<Object, String> pending = new HashMap<>();
    private BiConsumer<Object, Boolean> initializeReply = (id, success) -> { };
    private Receipt writing;
    private boolean stopped;

    GroundedStdioReplies(OutputStream output, Consumer<IOException> fail) {
        this.output = output;
        this.fail = fail;
    }

    synchronized void onInitializeReply(BiConsumer<Object, Boolean> callback) {
        initializeReply = callback;
    }

    void accepted(String method, Object id) {
        synchronized (pending) {
            pending.putIfAbsent(McpStdio.idKey(id), method);
        }
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
            String method;
            synchronized (pending) {
                method = pending.remove(McpStdio.idKey(writing.id()));
                pending.notifyAll();
            }
            if ("initialize".equals(method)) {
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
