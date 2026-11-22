package io.policynim.mcp.auth;

import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.util.StringUtils;

import java.util.Objects;

final class HostedMcpAuthStartupGuard implements SmartInitializingSingleton {

    private final PolicyNimProperties properties;
    private final McpTransport transport;

    HostedMcpAuthStartupGuard(PolicyNimProperties properties, McpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        PolicyNimProperties.McpProperties mcp = properties.getMcp();
        if (
            transport == McpTransport.STREAMABLE_HTTP
                && mcp.getAuth().isEnabled()
                && !StringUtils.hasText(mcp.getAuth().getBearerToken())
        ) {
            throw new IllegalStateException(
                "policynim.mcp.auth.bearer-token must be configured when streamable HTTP bearer auth is enabled."
            );
        }
    }
}
