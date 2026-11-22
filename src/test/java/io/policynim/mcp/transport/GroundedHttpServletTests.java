package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroundedHttpServletTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static GroundedHttpServlet servlet() {
        var settings = mock(McpServerSettings.class);
        when(settings.serverInfo()).thenReturn(new McpSchema.Implementation("fixture", "1"));
        when(settings.requestTimeout()).thenReturn(Duration.ofSeconds(3));
        return new GroundedHttpConfiguration().groundedHttpServlet(new StreamableHttpTransportMarker("/policy"),
            settings, new GroundedHttpAuthorities("127.0.0.1", () -> 8080, false, null), List.of(), mock(McpSyncToolRepository.class));
    }

    @Test
    void absentVersionSelectsSessionlessLegacyAndModernDiscoveryUsesItsOwnEnvelope() throws Exception {
        var servlet = servlet();
        try {
            var opening = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                "clientInfo":{"name":"fixture","version":"1"},"capabilities":{}}}
                """, "application/*");
            var initialized = send(servlet, opening);
            assertThat(initialized.getStatus()).isEqualTo(200);
            assertThat(initialized.getHeader("Mcp-Session-Id")).isNull();
            assertThat(body(initialized).at("/result/protocolVersion").asText()).isEqualTo("2025-11-25");
            assertThat(body(initialized).at("/result/instructions").asText()).startsWith("Search policy sources");
            var discover = modern("server/discover");
            var result = send(servlet, discover);
            assertThat(result.getStatus()).isEqualTo(200);
            assertThat(result.getHeader("Mcp-Session-Id")).isNull();
            assertThat(body(result).at("/result/instructions")).isEqualTo(body(initialized).at("/result/instructions"));
            assertThat(body(result).at("/result/resultType").asText()).isEqualTo("complete");
            assertThat(body(result).at("/result/supportedVersions/0").asText()).isEqualTo("2026-07-28");
        }
        finally {
            servlet.destroy();
        }
    }

    @Test
    void rejectsDuplicateRoutingHeadersWithTheRequestIdAndRecovers() throws Exception {
        var servlet = servlet();
        try {
            var request = modern("server/discover");
            request.addHeader("MCP-Protocol-Version", "2025-11-25");
            var rejected = send(servlet, request);
            assertThat(rejected.getStatus()).isEqualTo(400);
            assertThat(body(rejected).path("id").intValue()).isEqualTo(42);
            assertThat(body(rejected).at("/error/code").intValue()).isEqualTo(-32020);
            assertThat(send(servlet, modern("server/discover")).getStatus()).isEqualTo(200);
        }
        finally {
            servlet.destroy();
        }
    }

    @Test
    void unknownMethodsRetainTheirNameInErrorDataInEachProtocol() throws Exception {
        var servlet = servlet();
        try {
            for (boolean legacy : List.of(true, false)) {
                var request = legacy ? post("""
                    {"jsonrpc":"2.0","id":42,"method":"unregistered"}
                    """, "application/json") : modern("unregistered");
                var rejected = send(servlet, request);
                assertThat(rejected.getStatus()).isEqualTo(legacy ? 200 : 404);
                var frame = body(rejected);
                assertThat(frame.at("/error/code").asInt()).isEqualTo(-32601);
                assertThat(frame.at("/error/data").asText()).isEqualTo("unregistered");
                assertThat(frame.path("id").intValue()).isEqualTo(42);
            }
        }
        finally {
            servlet.destroy();
        }
    }

    private static MockHttpServletRequest modern(String method) {
        var request = post("""
            {"jsonrpc":"2.0","id":42,"method":"%s","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":
            "2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}
            """.formatted(method), "application/json");
        request.addHeader("MCP-Protocol-Version", "2026-07-28");
        request.addHeader("Mcp-Method", method);
        return request;
    }

    private static MockHttpServletRequest post(String body, String accept) {
        var request = new MockHttpServletRequest("POST", "/policy");
        request.addHeader("Host", "localhost");
        request.setContentType("application/json");
        request.addHeader("Accept", accept);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletResponse send(GroundedHttpServlet servlet, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        servlet.service(request, response);
        return response;
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray());
    }
}
