package io.policynim.mcp.auth;

import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.transport.McpServerBootstrap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class HostedMcpAuthConfiguration {

    @Bean
    HostedMcpAuthStartupGuard hostedMcpAuthStartupGuard(PolicyNimProperties properties, McpServerBootstrap bootstrap) {
        return new HostedMcpAuthStartupGuard(properties, bootstrap.transport());
    }
}
