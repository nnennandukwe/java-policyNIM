package io.policynim.mcp.transport;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;

final class LegacyTransportJson {
    private LegacyTransportJson() {
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(
            new SimpleModule().addSerializer(McpError.class, new TransportErrorSerializer()));
    }

    private static final class TransportErrorSerializer extends JsonSerializer<McpError> {
        @Override
        public void serialize(McpError error, JsonGenerator writer, SerializerProvider provider) throws IOException {
            var rpcError = error.getJsonRpcError();
            writer.writeStartObject();
            writer.writeObjectFieldStart("jsonRpcError");
            writer.writeNumberField("code", rpcError.code());
            writer.writeStringField("message", rpcError.code() == McpSchema.ErrorCodes.INTERNAL_ERROR
                ? "Error processing MCP request" : "Invalid MCP request");
            writer.writeEndObject();
            writer.writeEndObject();
        }
    }
}
