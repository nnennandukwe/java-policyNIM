package io.policynim.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads a single bounded UTF-8 frame and keeps any pipelined bytes in the same buffer. */
final class GroundedStdioFrames implements Closeable {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final String VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    record Frame(byte[] bytes, String method, Object id, boolean stamped, String proposedVersion,
        boolean validInitialize, Object cancelledId) {
        boolean isRequest() {
            return method != null && id != null;
        }
    }

    // Python retains up to eight earlier stream items, including parse failures. They don't reach a handler.
    static final Frame DROPPED = new Frame(null, null, null, false, null, false, null);

    private final BufferedInputStream input;
    private boolean afterCarriageReturn;

    GroundedStdioFrames(InputStream input) {
        this.input = new BufferedInputStream(input);
    }

    Frame next() throws IOException {
        var line = new ByteArrayOutputStream();
        boolean oversized = false;
        while (true) {
            int b = input.read();
            if (afterCarriageReturn) {
                afterCarriageReturn = false;
                if (b == '\n') {
                    continue;
                }
            }
            if (b == -1 && line.size() == 0 && !oversized) {
                return null;
            }
            if (b == -1 || b == '\n' || b == '\r') {
                afterCarriageReturn = b == '\r';
                if (oversized || line.size() == 0) {
                    return DROPPED;
                }
                line.write('\n');
                return decode(line.toByteArray());
            }
            if (line.size() < MAX_BYTES) {
                line.write(b);
            }
            else {
                oversized = true;
            }
        }
    }

    private Frame decode(byte[] bytes) {
        JsonNode node;
        try {
            node = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        }
        catch (JsonProcessingException invalid) {
            return DROPPED;
        }
        if (node == null || !"2.0".equals(node.path("jsonrpc").textValue())) {
            return DROPPED;
        }
        Object id = id(node.path("id"));
        if (node.has("result") || node.has("error")) {
            if (node.has("method") || node.has("params") || node.has("result") == node.has("error")) {
                return DROPPED;
            }
            boolean result = id != null && node.path("result").isObject();
            JsonNode error = node.path("error");
            boolean failure = (id != null || node.path("id").isNull()) && error.path("code").isIntegralNumber()
                && error.path("message").isTextual();
            return result || failure ? new Frame(bytes, null, null, false, null, false, null) : DROPPED;
        }
        String method = node.path("method").textValue();
        JsonNode params = node.path("params");
        if (method == null || (node.has("id") && id == null)
            || !(params.isObject() || params.isMissingNode() || params.isNull())) {
            return DROPPED;
        }
        String version = params.path("protocolVersion").textValue();
        JsonNode client = params.path("clientInfo");
        boolean validInitialize = version != null && params.path("capabilities").isObject()
            && client.path("name").isTextual() && client.path("version").isTextual();
        return new Frame(bytes, method, id, params.path("_meta").has(VERSION_KEY), version, validInitialize,
            id(params.path("requestId")));
    }

    private static Object id(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.numberValue();
        }
        return node.textValue();
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
