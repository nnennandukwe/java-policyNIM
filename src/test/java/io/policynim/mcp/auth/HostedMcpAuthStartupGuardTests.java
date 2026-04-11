package io.policynim.mcp.auth;

import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostedMcpAuthStartupGuardTests {

    @Test
    void failsClosedWhenStreamableHttpBearerAuthIsEnabledWithoutAToken() {
        PolicyNimProperties properties = new PolicyNimProperties();
        properties.getMcp().setTransport(McpTransport.STREAMABLE_HTTP);
        properties.getMcp().getAuth().setEnabled(true);
        properties.getMcp().getAuth().setBearerToken(" ");

        assertThatThrownBy(() -> new HostedMcpAuthStartupGuard(properties).afterSingletonsInstantiated())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("policynim.mcp.auth.bearer-token")
            .hasMessageContaining("streamable HTTP");
    }

    @Test
    void allowsStdioWithoutABearerToken() {
        PolicyNimProperties properties = new PolicyNimProperties();
        properties.getMcp().setTransport(McpTransport.STDIO);
        properties.getMcp().getAuth().setEnabled(true);

        assertThatCode(() -> new HostedMcpAuthStartupGuard(properties).afterSingletonsInstantiated())
            .doesNotThrowAnyException();
    }

    @Test
    void allowsDisabledBearerAuthWithoutAToken() {
        PolicyNimProperties properties = new PolicyNimProperties();
        properties.getMcp().setTransport(McpTransport.STREAMABLE_HTTP);
        properties.getMcp().getAuth().setEnabled(false);

        assertThatCode(() -> new HostedMcpAuthStartupGuard(properties).afterSingletonsInstantiated())
            .doesNotThrowAnyException();
    }
}
