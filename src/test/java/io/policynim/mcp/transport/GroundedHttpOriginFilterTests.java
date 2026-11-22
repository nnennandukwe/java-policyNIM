package io.policynim.mcp.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundedHttpOriginFilterTests {

    @ParameterizedTest
    @CsvSource({
        "0.0.0.0,8080,localhost:8000,http://localhost:8000,200",
        "::,8080,[::1]:9001,http://[::1]:9001,200",
        "192.0.2.20,8000,192.0.2.20:8000,http://192.0.2.20:8000,200",
        "2001:db8:0:0:0:0:0:20,8000,[2001:db8::20]:8000,http://[2001:db8::20]:8000,200",
        "MCP.Example,0,mcp.example:9123,http://mcp.example:9123,200",
        "mcp.example.,80,mcp.example.,http://mcp.example.,200",
        "192.0.2.20,8000,192.0.2.20,,421",
        "0.0.0.0,8000,0.0.0.0:8000,,421",
        "::ffff:0.0.0.0,8000,[::ffff:0.0.0.0]:8000,,421",
        "::ffff:255.255.255.255,8000,[::ffff:255.255.255.255]:8000,,421",
        "192.0.2.20,8000,192.0.2.20:8000,https://192.0.2.20:8000,403",
        "localhost,8080,localhost:8080,null,403",
        "localhost,8080,localhost:8080,http://localhost:65536,403",
        "localhost,8080,[invalid-address]:8080,,421",
        "localhost,8080,localhost:8080,http://[invalid-address]:8080,403"
    })
    void checksTheAnonymousBindAndLoopbackOrigins(String bindHost, int port, String host, String origin, int status)
        throws Exception {
        check(filter(bindHost, port, false, null), host, origin, status);
    }

    @ParameterizedTest
    @CsvSource({
        "mcp.example,https://mcp.example,200",
        "mcp.example:443,https://mcp.example:443,200",
        "mcp.example,,200",
        "mcp.example,http://mcp.example,403",
        "mcp.example:443,https://mcp.example:8443,403",
        "192.0.2.20:8000,,421",
        "mcp.example,https://elsewhere.example,403",
        ",,421"
    })
    void checksTheExplicitPublicOriginOnAuthenticatedServers(String host, String origin, int status) throws Exception {
        check(filter("192.0.2.20", 8000, true, URI.create("https://mcp.example/")),
            host, origin, status);
    }

    @Test
    void requiresAnExplicitPublicOriginWhenBearerAuthIsEnabled() {
        assertThatThrownBy(() -> filter("127.0.0.1", 8080, true, null))
            .hasMessageContaining("policynim.mcp.public-base-url");
    }

    @Test
    void requiresTheConfiguredNonDefaultPortForThePublicOrigin() throws Exception {
        var filter = filter("0.0.0.0", 8080, true, URI.create("https://mcp.example:8443"));
        check(filter, "mcp.example:8443", "https://mcp.example:8443", 200);
        check(filter, "mcp.example", null, 421);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/test", "https://example.test/path", "https://example.test?query",
        "https://example.test/#fragment", "https://user@example.test", "https://0.0.0.0"})
    void failsStartupWhenThePublicUrlIsntAServiceOrigin(String url) {
        assertThatThrownBy(() -> filter("127.0.0.1", 8080, false, URI.create(url)))
            .hasMessageContaining("policynim.mcp.public-base-url");
    }

    @Test
    void rejectsAmbiguousOriginsBeforeReachingTheTool() throws Exception {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Host", "localhost");
        request.addHeader("Origin", "http://localhost");
        request.addHeader("Origin", "https://elsewhere.example");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter("localhost", 8080, false, null).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    private static GroundedHttpOriginFilter filter(String bindHost, int port, boolean authenticated, URI publicOrigin) {
        var authorities = new GroundedHttpAuthorities(bindHost, () -> port == 0 ? 9123 : port, authenticated, publicOrigin);
        return new GroundedHttpOriginFilter(authorities);
    }

    private static void check(GroundedHttpOriginFilter filter, String host, String origin, int expectedStatus)
        throws Exception {
        var request = new MockHttpServletRequest("POST", "/mcp");
        if (host != null) {
            request.addHeader("Host", host);
        }
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        if (expectedStatus == 200) {
            assertThat(chain.getRequest()).isSameAs(request);
        }
        else {
            assertThat(chain.getRequest()).isNull();
            assertThat(response.getContentAsString()).isEqualTo(expectedStatus == 421 ? "Invalid Host header"
                : "Invalid Origin header");
        }
    }
}
