package io.policynim.mcp.transport;

import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
@ConditionalOnGroundedHttp
class GroundedHttpConfiguration {

    static final List<String> HANDSHAKE_VERSIONS = List.of(ProtocolVersions.MCP_2024_11_05,
        ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25);

    // Public server instructions at the pinned Python baseline.
    private static final String DEFAULT_INSTRUCTIONS = "Search policy sources or request citation-backed guidance before coding. "
        + "Treat insufficient_context as missing evidence; guidance does not execute changes.";

    @Bean(destroyMethod = "destroy")
    GroundedHttpServlet groundedHttpServlet(StreamableHttpTransportMarker endpoint, McpServerSettings settings,
        GroundedHttpAuthorities authorities,
        @Qualifier("groundedLegacyTools") List<McpStatelessServerFeatures.SyncToolSpecification> legacyTools,
        @Qualifier("groundedModernTools") McpSyncToolRepository modernTools) {
        var mapper = GroundedHttpJson.mapper();
        var legacy = HttpServletStatelessServerTransport.builder().messageEndpoint(endpoint.path())
            .httpHeaderValidator(authorities).jsonMapper(mapper).maxRequestSize(GroundedHttpServlet.MAX_BODY).build();
        var identity = settings.serverInfo();
        String instructions = Objects.requireNonNullElse(settings.instructions(), DEFAULT_INSTRUCTIONS);
        var modern = McpServer.builder()
            .serverInfo(io.modelcontextprotocol.modern.McpSchema.Implementation.builder(identity.name(), identity.version()).build())
            .instructions(instructions).jsonMapper(mapper);
        try {
            var legacyServer = io.modelcontextprotocol.server.McpServer.sync(new LegacyTransport(legacy));
            var capabilities = McpSchema.ServerCapabilities.builder();
            if (settings.enabled("capabilities.tool")) {
                capabilities.tools(false);
                legacyServer.tools(legacyTools);
                modern.feature(ToolsFeature.ofSync(modernTools));
            }
            legacyServer.serverInfo(identity).instructions(instructions).requestTimeout(settings.requestTimeout())
                .capabilities(capabilities.build()).immediateExecution(true).build();
            var modernServer = modern.build();
            try {
                var transport = HttpServletMcpTransport.builder(modernServer).httpHeaderValidator(authorities)
                    .jsonMapper(mapper).endpoint(endpoint.path())
                    .maxRequestSize(GroundedHttpServlet.MAX_BODY).build();
                return new GroundedHttpServlet(endpoint.path(), legacy, transport);
            }
            catch (RuntimeException | Error failure) {
                modernServer.closeGracefully();
                throw failure;
            }
        }
        catch (RuntimeException | Error failure) {
            legacy.destroy();
            throw failure;
        }
    }

    // Stateless HTTP can receive the same legacy initialize offers as stdio, including 2024-11-05.
    private record LegacyTransport(HttpServletStatelessServerTransport servlet) implements McpStatelessServerTransport {
        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            servlet.setMcpHandler(handler);
        }

        @Override
        public List<String> protocolVersions() {
            return HANDSHAKE_VERSIONS;
        }

        @Override
        public Mono<Void> closeGracefully() {
            return servlet.closeGracefully();
        }
    }

    @Bean
    ServletRegistrationBean<GroundedHttpServlet> groundedEndpoint(GroundedHttpServlet servlet,
        StreamableHttpTransportMarker endpoint) {
        String path = endpoint.path();
        var registration = new ServletRegistrationBean<>(servlet, path, path + "/");
        registration.setName("groundedMcp");
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    ApplicationListener<ContextClosedEvent> closeGroundedSessionsBeforeHttpDrain(
        ApplicationContext context, GroundedHttpServlet servlet
    ) {
        return LegacyHttpTransportConfiguration.beforeHttpDrain(context, servlet::destroy);
    }
}
