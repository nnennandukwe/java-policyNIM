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
        properties.getMcp().getAuth().setEnabled(true);
        properties.getMcp().getAuth().setBearerToken(" ");

        assertThatThrownBy(() -> new HostedMcpAuthStartupGuard(properties, McpTransport.STREAMABLE_HTTP).afterSingletonsInstantiated())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("policynim.mcp.auth.bearer-token")
            .hasMessageContaining("streamable HTTP");
    }

    @Test
    void allowsStdioWithoutABearerToken() {
        PolicyNimProperties properties = new PolicyNimProperties();
        properties.getMcp().getAuth().setEnabled(true);

        assertThatCode(() -> new HostedMcpAuthStartupGuard(properties, McpTransport.STDIO).afterSingletonsInstantiated())
            .doesNotThrowAnyException();
    }

    @Test
    void allowsDisabledBearerAuthWithoutAToken() {
        PolicyNimProperties properties = new PolicyNimProperties();
        properties.getMcp().getAuth().setEnabled(false);

        assertThatCode(() -> new HostedMcpAuthStartupGuard(properties, McpTransport.STREAMABLE_HTTP).afterSingletonsInstantiated())
            .doesNotThrowAnyException();
    }
}
