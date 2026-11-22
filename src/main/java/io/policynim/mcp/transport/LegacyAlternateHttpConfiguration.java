package io.policynim.mcp.transport;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(StreamableHttpTransportCondition.class)
class LegacyAlternateHttpConfiguration {

    private static final String PREFIX = "spring.ai.mcp.server.";

    // The SSE servlet remains available to clients that explicitly select this legacy protocol.
    @Bean
    @ConditionalOnProperty(name = LegacyHttpProtocol.PROPERTY, havingValue = "SSE")
    ServletRegistrationBean<HttpServletSseServerTransportProvider> legacySseServlet(Environment environment) {
        String events = environment.getProperty(PREFIX + "sse-endpoint", "/sse");
        String messages = environment.getProperty(PREFIX + "sse-message-endpoint", "/mcp/message");
        var transport = HttpServletSseServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(LegacyTransportJson.objectMapper()))
            .baseUrl(environment.getProperty(PREFIX + "base-url", ""))
            .sseEndpoint(events)
            .messageEndpoint(messages)
            .keepAliveInterval(Binder.get(environment).bind(PREFIX + "keep-alive-interval", Duration.class).orElse(null))
            .build();
        var registration = new ServletRegistrationBean<>(transport, events, messages);
        registration.setAsyncSupported(true);
        registration.setName("policyNimSse");
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    @ConditionalOnProperty(name = LegacyHttpProtocol.PROPERTY, havingValue = "SSE")
    McpSyncServer legacySseServer(ServletRegistrationBean<HttpServletSseServerTransportProvider> registration,
        LegacyMcpServerConfiguration settings) {
        return settings.build(McpServer.sync(registration.getServlet()));
    }

    @Bean
    @ConditionalOnProperty(name = LegacyHttpProtocol.PROPERTY, havingValue = "STATELESS")
    ServletRegistrationBean<HttpServletStatelessServerTransport> legacyStatelessServlet(StreamableHttpTransportMarker marker) {
        var transport = HttpServletStatelessServerTransport.builder()
            .jsonMapper(new JacksonMcpJsonMapper(LegacyTransportJson.objectMapper()))
            .messageEndpoint(marker.path())
            .build();
        var registration = new ServletRegistrationBean<>(transport, marker.path());
        registration.setName("policyNimStateless");
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    @ConditionalOnProperty(name = LegacyHttpProtocol.PROPERTY, havingValue = "STATELESS")
    McpStatelessSyncServer legacyStatelessServer(ServletRegistrationBean<HttpServletStatelessServerTransport> registration,
        LegacyMcpServerConfiguration settings, Environment environment) {
        // Spring AI registered the Java tools only on its stateful servers.
        var capabilities = McpSchema.ServerCapabilities.builder();
        if (settings.enabled("capabilities.tool")) {
            capabilities.tools(false);
        }
        if (settings.enabled("capabilities.resource")) {
            capabilities.resources(false, false);
        }
        if (settings.enabled("capabilities.prompt")) {
            capabilities.prompts(false);
        }
        if (settings.enabled("capabilities.completion")) {
            capabilities.completions();
        }
        return McpServer.sync(registration.getServlet())
            .serverInfo(settings.serverInfo())
            .instructions(environment.getProperty(PREFIX + "instructions"))
            .requestTimeout(settings.requestTimeout())
            .capabilities(capabilities.build())
            .immediateExecution(true)
            .build();
    }
}
