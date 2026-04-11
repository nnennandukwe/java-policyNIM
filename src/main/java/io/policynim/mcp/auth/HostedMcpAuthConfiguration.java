package io.policynim.mcp.auth;

import io.policynim.config.PolicyNimProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class HostedMcpAuthConfiguration {

    @Bean
    HostedMcpAuthStartupGuard hostedMcpAuthStartupGuard(PolicyNimProperties properties) {
        return new HostedMcpAuthStartupGuard(properties);
    }
}
