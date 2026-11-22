package io.policynim.mcp.transport;

import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
public class McpBootstrapConfiguration {

    @Bean
    McpServerSettings mcpServerSettings(Environment environment, PolicyNimProperties properties) {
        return new McpServerSettings(environment, properties);
    }

    @Bean
    @ConditionalOnGroundedHttp
    GroundedHttpAuthorities groundedHttpAuthorities(PolicyNimProperties properties, Environment environment) {
        var mcp = properties.getMcp();
        int port = environment.getProperty("server.port", Integer.class, mcp.getPort());
        return new GroundedHttpAuthorities(environment.getProperty("server.address", mcp.getHost()),
            () -> port == 0 ? environment.getProperty("local.server.port", Integer.class, 0) : port,
            mcp.getAuth().isEnabled(), mcp.getPublicBaseUrl());
    }

    @Bean
    @ConditionalOnGroundedHttp
    FilterRegistrationBean<GroundedHttpOriginFilter> groundedHttpAuthorityCheck(
        GroundedHttpAuthorities authorities, StreamableHttpTransportMarker endpoint
    ) {
        var registration = new FilterRegistrationBean<>(new GroundedHttpOriginFilter(authorities));
        registration.addUrlPatterns(endpoint.path(), endpoint.path() + "/");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    McpServerBootstrap mcpServerBootstrap(
        PolicyNimProperties properties, Optional<StreamableHttpTransportMarker> http
    ) {
        if (http.isPresent()) {
            return new SimpleMcpServerBootstrap(
                McpTransport.STREAMABLE_HTTP, "streamable-http MCP server on " + http.get().path()
            );
        }
        return new SimpleMcpServerBootstrap(McpTransport.STDIO, "stdio MCP server for " + properties.getMcp().getName());
    }

    @Bean
    @Conditional(StreamableHttpTransportCondition.class)
    StreamableHttpTransportMarker streamableHttpTransportMarker(PolicyNimProperties properties, Environment environment) {
        String path = environment.getProperty(
            "spring.ai.mcp.server.streamable-http.mcp-endpoint", properties.getMcp().getStreamableHttpPath()
        );
        return new StreamableHttpTransportMarker(path);
    }

    private record SimpleMcpServerBootstrap(McpTransport transport, String description)
        implements McpServerBootstrap {
    }
}
