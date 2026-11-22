package io.policynim.mcp.transport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.util.StdConverter;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc;
import io.modelcontextprotocol.spec.McpSchema;

final class GroundedMcpJson {
    private GroundedMcpJson() { }

    static JacksonMcpJsonMapper mapper() {
        var json = LegacyTransportJson.objectMapper();
        json.addMixIn(McpSchema.JSONRPCResponse.JSONRPCError.class, ErrorFormat.class);
        json.addMixIn(JsonRpc.JSONRPCResponse.JSONRPCError.class, ErrorFormat.class);
        return new JacksonMcpJsonMapper(json);
    }

    @JsonSerialize(converter = ErrorConverter.class)
    private interface ErrorFormat { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RpcError(int code, String message, Object data) { }

    static final class ErrorConverter extends StdConverter<Object, RpcError> {
        @Override
        public RpcError convert(Object value) {
            return switch (value) {
                case McpSchema.JSONRPCResponse.JSONRPCError legacy -> error(legacy.code(), legacy.message(), legacy.data());
                case JsonRpc.JSONRPCResponse.JSONRPCError modern -> error(modern.code(), modern.message(), modern.data());
                default -> throw new IllegalArgumentException("Expected an SDK JSON-RPC error");
            };
        }

        private static RpcError error(int code, String message, Object data) {
            // Both SDK handlers append the requested method to their diagnostic. The public protocol
            // returns that name as structured data, including when it contains spaces or punctuation.
            String prefix = "Method not found: ";
            if (code == McpSchema.ErrorCodes.METHOD_NOT_FOUND && data == null && message.startsWith(prefix)) {
                return new RpcError(code, "Method not found", message.substring(prefix.length()));
            }
            return new RpcError(code, message, data);
        }
    }
}
