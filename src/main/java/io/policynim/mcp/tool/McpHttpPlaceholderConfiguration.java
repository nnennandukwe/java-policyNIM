package io.policynim.mcp.tool;

import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.transport.StreamableHttpTransportMarker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpHttpPlaceholderConfiguration {

    @Bean
    @ConditionalOnBean(StreamableHttpTransportMarker.class)
    McpHttpPlaceholderController mcpHttpPlaceholderController(
        PolicyNimProperties properties,
        StreamableHttpTransportMarker transportMarker
    ) {
        return new McpHttpPlaceholderController(properties, transportMarker);
    }
}
