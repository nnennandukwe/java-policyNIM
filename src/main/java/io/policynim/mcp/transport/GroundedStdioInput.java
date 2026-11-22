package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpStdio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Selects one SDK from the opening request and exposes only that connection's accepted frames. */
final class GroundedStdioInput extends InputStream {
    enum Era { LEGACY, MODERN }

    @FunctionalInterface
    interface ErrorWriter {
        void write(byte[] line) throws IOException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PREFIX_LIMIT = 8;

    private final GroundedStdioFrames source;
    private final ErrorWriter replies;
    private final BiPredicate<String, Object> onRequest;
    private final Consumer<Object> onCancel;
    private final boolean toolsEnabled;
    private final long initializeWaitNanos;
    private final ArrayDeque<GroundedStdioFrames.Frame> replay = new ArrayDeque<>();
    private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);
    private Era era;
    private boolean selected;
    private volatile boolean closed;

    private Object pendingInit;
    private volatile boolean initialized;
    private volatile boolean initializeRefused;

    GroundedStdioInput(InputStream source, ErrorWriter replies, BiPredicate<String, Object> onRequest,
        Consumer<Object> onCancel, boolean toolsEnabled, Duration initializeTimeout) {
        this.source = new GroundedStdioFrames(source);
        this.replies = replies;
        this.onRequest = onRequest;
        this.onCancel = onCancel;
        this.toolsEnabled = toolsEnabled;
        initializeWaitNanos = initializeTimeout.toNanos();
    }

    Era select() throws IOException {
        if (selected) {
            return era;
        }
        selected = true;
        GroundedStdioFrames.Frame frame;
        while (!closed && (frame = source.next()) != null) {
            if (frame.isRequest()) {
                era = !"initialize".equals(frame.method()) && frame.stamped() ? Era.MODERN : Era.LEGACY;
                replay.addLast(frame);
                return era;
            }
            if (replay.size() < PREFIX_LIMIT) {
                replay.addLast(frame);
            }
        }
        replay.clear();
        return null;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        return advance() ? current.read(bytes, offset, length) : -1;
    }

    @Override
    public int read() throws IOException {
        return advance() ? current.read() : -1;
    }

    private boolean advance() throws IOException {
        if (!selected) {
            throw new IOException("Select the stdio protocol before attaching its SDK.");
        }
        while (!closed && era != null && current.available() == 0) {
            var frame = replay.pollFirst();
            if (frame == null) {
                frame = source.next();
            }
            if (frame == null) {
                return false;
            }
            if (frame != GroundedStdioFrames.DROPPED && forward(frame)) {
                current = new ByteArrayInputStream(frame.bytes());
            }
        }
        return !closed && current.available() != 0;
    }

    private boolean forward(GroundedStdioFrames.Frame frame) throws IOException {
        if (!frame.isRequest()) {
            // Null-ID errors can't answer a server request. The legacy SDK rejects them in its message constructor.
            if (era == Era.LEGACY && frame.method() == null && frame.id() == null) {
                return false;
            }
            if (era == Era.LEGACY && "notifications/initialized".equals(frame.method())) {
                awaitInitializeReply();
                if (closed || initialized || initializeRefused) {
                    return false;
                }
                initialized = true;
            }
            else if (era == Era.MODERN && "notifications/cancelled".equals(frame.method()) && frame.cancelledId() != null) {
                onCancel.accept(frame.cancelledId());
            }
            return true;
        }
        boolean handshake = "initialize".equals(frame.method());
        // The two SDK request constructors accept Integer and Long, while the wire ID can be wider.
        if (frame.hasWideId()) {
            if (era == Era.LEGACY && handshake) {
                initializeRefused = true;
            }
            write(error(frame, -32600, "Numeric request ID exceeds the supported signed 64-bit range."));
            return false;
        }
        if (era == Era.MODERN && handshake) {
            var error = error(frame, -32022, "The connection serves the modern protocol and cannot initialize.");
            var data = error.withObject("/error/data");
            data.putArray("supported").add(McpSchema.LATEST_PROTOCOL_VERSION);
            if (frame.proposedVersion() != null) {
                data.put("requested", frame.proposedVersion());
            }
            write(error);
            return false;
        }
        if (era == Era.LEGACY) {
            if (!handshake && frame.stamped()) {
                write(error(frame, -32600, "The connection serves the handshake protocol."));
                return false;
            }
            if (!legacyRequest(frame, handshake)) {
                return false;
            }
        }
        if (onRequest.test(frame.method(), frame.id())) {
            return true;
        }
        if (handshake) {
            initialized(frame.id(), false);
        }
        return false;
    }

    private boolean legacyRequest(GroundedStdioFrames.Frame frame, boolean handshake) throws IOException {
        awaitInitializeReply();
        if (closed) {
            return false;
        }
        if (handshake) {
            if (frame.validInitialize()) {
                synchronized (this) {
                    pendingInit = McpStdio.idKey(frame.id());
                }
                return true;
            }
            initializeRefused = true;
            invalidParams(frame);
            return false;
        }
        if (!initialized) {
            if ("ping".equals(frame.method())) {
                var response = envelope(frame);
                response.putObject("result");
                write(response);
                return false;
            }
            if (toolsEnabled && ("tools/list".equals(frame.method()) || "tools/call".equals(frame.method()))) {
                invalidParams(frame);
                return false;
            }
        }
        return true;
    }

    synchronized void initialized(Object id, boolean success) {
        if (pendingInit != null && pendingInit.equals(McpStdio.idKey(id))) {
            pendingInit = null;
            initializeRefused = !success;
            if (success) {
                initialized = true;
            }
            notifyAll();
        }
    }

    private synchronized void awaitInitializeReply() throws IOException {
        long deadline = System.nanoTime() + initializeWaitNanos;
        while (pendingInit != null && !closed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("Timed out waiting for the stdio initialize reply.");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for the stdio initialize reply.");
            }
        }
    }

    private void invalidParams(GroundedStdioFrames.Frame frame) throws IOException {
        var response = error(frame, -32602, "Invalid request parameters");
        response.withObject("/error").put("data", "");
        write(response);
    }

    private static ObjectNode error(GroundedStdioFrames.Frame frame, int code, String message) {
        var response = envelope(frame);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private static ObjectNode envelope(GroundedStdioFrames.Frame frame) {
        var response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", JSON.valueToTree(frame.id()));
        return response;
    }

    private void write(ObjectNode message) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(message);
        byte[] line = Arrays.copyOf(bytes, bytes.length + 1);
        line[bytes.length] = '\n';
        replies.write(line);
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        source.close();
    }
}
