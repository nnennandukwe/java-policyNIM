package io.policynim.mcp.health;

import io.policynim.config.PolicyNimProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuntimeReadinessConfiguration {

    @Bean
    RuntimeReadinessService runtimeReadinessService(PolicyNimProperties properties) {
        return new BootstrapRuntimeReadinessService(properties);
    }
}
