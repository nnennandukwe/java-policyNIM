package io.policynim.mcp.transport;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

final class LegacyTransportJson {
    private LegacyTransportJson() {
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(
            new SimpleModule().addSerializer(McpError.class, new TransportErrorSerializer()));
    }

    private static final class TransportErrorSerializer extends JsonSerializer<McpError> {
        private static final String EVENT_STREAM = "text/event-stream required in Accept header";
        private static final String JSON = "application/json required in Accept header";
        private static final String SESSION = "Session ID required in mcp-session-id header";
        // The SDK reports missing Accept types and the session header together, in this order.
        private static final Set<String> HEADER_AND_SESSION_ERRORS = Set.of(
            EVENT_STREAM, JSON, SESSION, EVENT_STREAM + "; " + JSON, EVENT_STREAM + "; " + SESSION,
            JSON + "; " + SESSION, EVENT_STREAM + "; " + JSON + "; " + SESSION,
            "Both application/json and text/event-stream required in Accept header", "Session ID missing in message endpoint");
        private static final Set<String> REQUEST_ERRORS = Set.of(
            "Unsupported Media Type: Content-Type must be application/json", "Invalid message format",
            "The server accepts either requests or notifications", "Unknown message type");

        @Override
        public void serialize(McpError error, JsonGenerator writer, SerializerProvider provider) throws IOException {
            var rpcError = error.getJsonRpcError();
            writer.writeStartObject();
            writer.writeObjectFieldStart("jsonRpcError");
            writer.writeNumberField("code", rpcError.code());
            writer.writeStringField("message", publicMessage(rpcError.code(), rpcError.message()));
            writer.writeEndObject();
            writer.writeEndObject();
        }

        private static String publicMessage(int code, String diagnostic) {
            String message = Objects.toString(diagnostic, "");
            if (code == McpSchema.ErrorCodes.INTERNAL_ERROR) {
                return message.startsWith("Session not found: ") ? "Session not found" : "Error processing MCP request";
            }
            if (code == McpSchema.ErrorCodes.METHOD_NOT_FOUND && HEADER_AND_SESSION_ERRORS.contains(message)) {
                return message;
            }
            if (code == McpSchema.ErrorCodes.INVALID_REQUEST) {
                if (message.startsWith("Invalid message format: ")) {
                    return "Invalid message format";
                }
                if (REQUEST_ERRORS.contains(message)) {
                    return message;
                }
            }
            return "Invalid MCP request";
        }
    }
}
