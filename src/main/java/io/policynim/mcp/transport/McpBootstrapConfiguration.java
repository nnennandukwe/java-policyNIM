package io.policynim.mcp.transport;

import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpBootstrapConfiguration {

    @Bean
    McpServerBootstrap mcpServerBootstrap(PolicyNimProperties properties) {
        return switch (properties.getMcp().getTransport()) {
            case STDIO -> new SimpleMcpServerBootstrap(
                McpTransport.STDIO,
                "stdio bootstrap placeholder for server " + properties.getMcp().getName()
            );
            case STREAMABLE_HTTP -> new SimpleMcpServerBootstrap(
                McpTransport.STREAMABLE_HTTP,
                "streamable-http bootstrap placeholder on " + properties.getMcp().getStreamableHttpPath()
            );
        };
    }

    @Bean
    @Conditional(StreamableHttpTransportCondition.class)
    StreamableHttpTransportMarker streamableHttpTransportMarker(PolicyNimProperties properties) {
        return new StreamableHttpTransportMarker(properties.getMcp().getStreamableHttpPath());
    }

    private record SimpleMcpServerBootstrap(McpTransport transport, String description)
        implements McpServerBootstrap {
    }
}
