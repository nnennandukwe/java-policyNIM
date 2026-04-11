package io.policynim.mcp.auth;

import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.util.StringUtils;

import java.util.Objects;

final class HostedMcpAuthStartupGuard implements SmartInitializingSingleton {

    private final PolicyNimProperties properties;

    HostedMcpAuthStartupGuard(PolicyNimProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        PolicyNimProperties.McpProperties mcp = properties.getMcp();
        if (
            mcp.getTransport() == McpTransport.STREAMABLE_HTTP
                && mcp.getAuth().isEnabled()
                && !StringUtils.hasText(mcp.getAuth().getBearerToken())
        ) {
            throw new IllegalStateException(
                "policynim.mcp.auth.bearer-token must be configured when streamable HTTP bearer auth is enabled."
            );
        }
    }
}
