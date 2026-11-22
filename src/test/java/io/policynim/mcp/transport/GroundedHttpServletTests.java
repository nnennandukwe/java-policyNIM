package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.policynim.config.PolicyNimProperties;
import jakarta.servlet.ServletOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GroundedHttpServletTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static GroundedHttpServlet servlet() {
        var settings = new McpServerSettings(new MockEnvironment()
            .withProperty("spring.ai.mcp.server.name", "fixture")
            .withProperty("spring.ai.mcp.server.version", "1")
            .withProperty("spring.ai.mcp.server.request-timeout", "3s"), new PolicyNimProperties());
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

    @Test
    void distinguishesEmptyBodiesFromInvalidEnvelopesAndCorrelatesVersionDisagreement() throws Exception {
        var servlet = servlet();
        try {
            for (boolean modern : List.of(true, false)) {
                for (String value : List.of(" ", "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"params\":[]}")) {
                    var invalid = post(value, "application/json");
                    if (modern) {
                        invalid.addHeader("MCP-Protocol-Version", "2026-07-28");
                    }
                    var reply = send(servlet, invalid);
                    assertThat(reply.getStatus()).isEqualTo(400);
                    assertThat(body(reply).path("id").isNull()).isTrue();
                    int expectedCode = value.isBlank() ? -32700 : modern ? -32600 : -32602;
                    assertThat(body(reply).at("/error/code").asInt()).isEqualTo(expectedCode);
                }
            }
            var badVersion = modern("server/discover");
            badVersion.setContent("""
                {"jsonrpc":"2.0","id":42,"method":"server/discover","params":{"_meta":{
                "io.modelcontextprotocol/protocolVersion":null,"io.modelcontextprotocol/clientCapabilities":{}}}}
                """.getBytes(StandardCharsets.UTF_8));
            var reply = body(send(servlet, badVersion));
            assertThat(reply.path("id").asInt()).isEqualTo(42);
            assertThat(reply.at("/error/code").asInt()).isEqualTo(-32020);
            assertThat(send(servlet, modern("server/discover")).getStatus()).isEqualTo(200);
        }
        finally {
            servlet.destroy();
        }
    }

    @Test
    void acceptsJsonRangesRegardlessOfQualityLikeThePinnedReference() throws Exception {
        var servlet = servlet();
        try {
            for (String accepted : List.of("application/json;q=0", "application/json;q=0, */*;q=1",
                "*/*;q=1, application/*;q=0", "ApPlIcAtIoN/JsOn; profile=public", "application/*;q=0")) {
                var request = modern("server/discover");
                request.removeHeader("Accept");
                request.addHeader("Accept", accepted);
                assertThat(send(servlet, request).getStatus()).as(accepted).isEqualTo(200);
            }
            var unsupported = modern("server/discover");
            unsupported.removeHeader("Accept");
            unsupported.addHeader("Accept", "text/plain");
            assertThat(send(servlet, unsupported).getStatus()).isEqualTo(406);
        }
        finally {
            servlet.destroy();
        }
    }

    @Test
    void allowsOnlyGetAndPostForLegacyRequestsAndPostForModernRequests() throws Exception {
        var servlet = servlet();
        try {
            for (String method : List.of("HEAD", "DELETE", "PATCH")) {
                var request = new MockHttpServletRequest(method, "/policy");
                var legacy = send(servlet, request);
                assertThat(legacy.getStatus()).isEqualTo(405);
                assertThat(legacy.getHeader("Allow")).isEqualTo("GET, POST");
                request.addHeader("MCP-Protocol-Version", "2026-07-28");
                var modern = send(servlet, request);
                assertThat(modern.getStatus()).isEqualTo(405);
                assertThat(modern.getHeader("Allow")).isEqualTo("POST");
            }
        }
        finally {
            servlet.destroy();
        }
    }

    @Test
    void rejectsExcessLegacyListenersBeforeStartingAsyncAndReusesReleasedCapacity() throws Exception {
        var listeners = new GroundedHttpListeners(1);
        var first = new MockHttpServletRequest("GET", "/policy");
        first.setAsyncSupported(true);
        var second = new MockHttpServletRequest("GET", "/policy");
        second.setAsyncSupported(true);
        var response = asyncResponse();
        try {
            listeners.open(first, response);
            assertThat(first.isAsyncStarted()).isTrue();
            var full = asyncResponse();
            listeners.open(second, full);
            assertThat(full.getStatus()).isEqualTo(429);
            assertThat(full.getHeader("Retry-After")).isEqualTo("15");
            assertThat(second.isAsyncStarted()).isFalse();

            first.getAsyncContext().complete();
            listeners.open(second, response);
            assertThat(second.isAsyncStarted()).isTrue();
        }
        finally {
            listeners.close();
        }
        assertThat(second.isAsyncStarted()).isFalse();
        var closed = new MockHttpServletResponse();
        listeners.open(new MockHttpServletRequest("GET", "/policy"), closed);
        assertThat(closed.getStatus()).isEqualTo(503);
    }

    private static MockHttpServletResponse asyncResponse() {
        var output = mock(ServletOutputStream.class);
        return new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return output;
            }
        };
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
