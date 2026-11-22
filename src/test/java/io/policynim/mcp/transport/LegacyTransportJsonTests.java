package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyTransportJsonTests {

    private final ObjectMapper mapper = LegacyTransportJson.objectMapper();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        -32601 | Session ID missing in message endpoint
        -32601 | Session ID required in mcp-session-id header
        -32601 | text/event-stream required in Accept header; Session ID required in mcp-session-id header
        -32601 | text/event-stream required in Accept header; application/json required in Accept header
        -32601 | Both application/json and text/event-stream required in Accept header
        -32600 | Unsupported Media Type: Content-Type must be application/json
        -32600 | Invalid message format
        """)
    void preservesPublicRequestDiagnostics(int code, String diagnostic) throws Exception {
        assertPublicError(code, diagnostic, diagnostic);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        -32603 | Session not found: fixture-session | Session not found
        -32600 | Invalid message format: fixture-parser-detail | Invalid message format
        -32603 | Failed to handle request: fixture-detail | Error processing MCP request
        -32603 | Session ID required in mcp-session-id header | Error processing MCP request
        -32601 | text/event-stream required in Accept header; fixture-detail | Invalid MCP request
        -32600 | fixture-request-detail | Invalid MCP request
        """)
    void removesCallerAndExceptionDetails(int code, String diagnostic, String expectedMessage) throws Exception {
        assertPublicError(code, diagnostic, expectedMessage);
    }

    private void assertPublicError(int code, String diagnostic, String expectedMessage) throws Exception {
        var error = McpError.builder(code).message(diagnostic).data(Map.of("field", "fixture-data")).build();
        error.initCause(new IllegalStateException("fixture-cause"));
        assertThat(mapper.readTree(mapper.writeValueAsBytes(error)))
            .isEqualTo(mapper.valueToTree(Map.of("jsonRpcError", Map.of("code", code, "message", expectedMessage))));
    }
}
