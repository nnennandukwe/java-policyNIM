package io.policynim.mcp.transport;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

final class GroundedHttpRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    GroundedHttpRequest(HttpServletRequest original, byte[] body) {
        super(original);
        this.body = body;
    }

    // The legacy SDK requires both names. The public JSON response has already been negotiated.
    @Override
    public String getHeader(String name) {
        return "Accept".equalsIgnoreCase(name) ? "application/json, text/event-stream" : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return "Accept".equalsIgnoreCase(name) ? Collections.enumeration(List.of(getHeader(name))) : super.getHeaders(name);
    }

    @Override
    public ServletInputStream getInputStream() {
        var bytes = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() { return bytes.read(); }

            @Override
            public int read(byte[] buffer, int offset, int length) { return bytes.read(buffer, offset, length); }

            @Override
            public boolean isFinished() { return bytes.available() == 0; }

            @Override
            public boolean isReady() { return true; }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("The SDK reads the bounded HTTP body synchronously.");
            }
        };
    }

    record Envelope(String version, JsonNode id, String method, boolean response, JsonNode metadata) {
        boolean validRequest() {
            return "2.0".equals(version) && method != null
                && (id == null || id.isTextual() || id.isIntegralNumber());
        }
    }

    // Scan only the envelope and the reserved metadata. Tool arguments stay with the SDK's decoder.
    Envelope envelope(ObjectMapper json) throws IOException {
        String version = null;
        JsonNode id = null;
        String method = null;
        boolean response = false;
        JsonNode metadata = null;
        try (var parser = json.createParser(body)) {
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    switch (field) {
                        case "id" -> id = value.isScalarValue() ? json.readTree(parser) : json.nullNode();
                        case "jsonrpc" -> version = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                        case "method" -> method = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                        case "result", "error" -> response = true;
                        case "params" -> metadata = metadata(parser, value, json);
                        default -> { }
                    }
                    parser.skipChildren();
                }
            }
            else {
                parser.skipChildren();
            }
            if (parser.nextToken() != null) {
                throw new IOException("Expected a single JSON value");
            }
        }
        return new Envelope(version, id, method, response, metadata);
    }

    private static JsonNode metadata(JsonParser parser, JsonToken value, ObjectMapper json) throws IOException {
        JsonNode metadata = null;
        if (value == JsonToken.START_OBJECT) {
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("_meta".equals(field)) {
                    metadata = json.readTree(parser);
                }
                parser.skipChildren();
            }
        }
        return metadata;
    }
}
