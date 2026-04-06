package io.policynim.mcp.transport;

import io.policynim.config.PolicyNimProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpBootstrapConfiguration {

    @Bean
    @ConditionalOnProperty(name = "policynim.mcp.transport", havingValue = "stdio")
    McpServerBootstrap stdioMcpServerBootstrap(PolicyNimProperties properties) {
        return new SimpleMcpServerBootstrap(
            McpTransport.STDIO,
            "stdio bootstrap placeholder for server " + properties.getMcp().getName()
        );
    }

    @Bean
    @ConditionalOnProperty(
        name = "policynim.mcp.transport",
        havingValue = "streamable-http",
        matchIfMissing = true
    )
    McpServerBootstrap streamableHttpMcpServerBootstrap(PolicyNimProperties properties) {
        return new SimpleMcpServerBootstrap(
            McpTransport.STREAMABLE_HTTP,
            "streamable-http bootstrap placeholder on " + properties.getMcp().getStreamableHttpPath()
        );
    }

    private record SimpleMcpServerBootstrap(McpTransport transport, String description)
        implements McpServerBootstrap {
    }
}
