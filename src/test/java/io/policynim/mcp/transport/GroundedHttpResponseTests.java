package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GroundedHttpResponseTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
        "\"id\":1,\"result\":{}",
        "\"id\":\"client\",\"result\":{\"value\":null}",
        "\"id\":null,\"error\":{\"code\":-32602,\"message\":\"Invalid params\",\"data\":{\"reason\":\"missing\"}}",
        "\"id\":0,\"error\":{\"code\":\"-32602\",\"message\":\"Invalid params\"}",
        "\"id\":1,\"result\":{},\"error\":{}"
    })
    void acceptsLegacyResponsesAndRejectsThemOnTheModernTransport(String fields) throws Exception {
        var legacy = post(fields, "2025-11-25");
        assertThat(legacy.getStatus()).isEqualTo(202);
        assertThat(legacy.getContentType()).isEqualTo("application/json");
        assertThat(legacy.getContentAsByteArray()).isEmpty();
        assertRejection(post(fields, "2026-07-28"), -32600);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"id\":1,\"result\":null",
        "\"id\":1,\"result\":7",
        "\"id\":1,\"result\":[]",
        "\"id\":null,\"result\":{}",
        "\"id\":true,\"result\":{}",
        "\"id\":{},\"error\":{\"code\":0,\"message\":\"message\"}",
        "\"id\":1,\"error\":{}",
        "\"id\":1,\"error\":{\"message\":\"missing code\"}",
        "\"id\":1,\"error\":{\"code\":1}",
        "\"id\":1,\"error\":{\"code\":1.5,\"message\":\"fractional code\"}",
        "\"id\":1,\"error\":{\"code\":\"bad\",\"message\":\"invalid code\"}",
        "\"id\":1,\"error\":{\"code\":1,\"message\":2}"
    })
    void rejectsIncompleteLegacyResponses(String fields) throws Exception {
        assertRejection(post(fields, "2025-11-25"), -32602);
    }

    private void assertRejection(MockHttpServletResponse response, int code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(400);
        var frame = JSON.readTree(response.getContentAsByteArray());
        assertThat(frame.required("id").isNull()).isTrue();
        assertThat(frame.required("error").required("code").intValue()).isEqualTo(code);
    }

    private MockHttpServletResponse post(String fields, String version) throws Exception {
        var servlet = new GroundedHttpServlet("/mcp", mock(HttpServletStatelessServerTransport.class),
            mock(HttpServletMcpTransport.class));
        try {
            var request = new MockHttpServletRequest("POST", "/mcp");
            request.setContentType("application/json");
            request.addHeader("Accept", "application/json");
            request.addHeader("MCP-Protocol-Version", version);
            request.setContent(("{\"jsonrpc\":\"2.0\"," + fields + "}").getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            servlet.service(request, response);
            return response;
        }
        finally {
            servlet.destroy();
        }
    }
}
