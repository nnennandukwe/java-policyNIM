package io.policynim.mcp.transport;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
class LegacyMcpServerConfiguration {

    private static final String PREFIX = "spring.ai.mcp.server.";

    private final Environment environment;
    private final PolicyNimProperties properties;
    private final List<McpServerFeatures.SyncToolSpecification> tools;

    LegacyMcpServerConfiguration(Environment environment, PolicyNimProperties properties,
        @Qualifier("policySyncToolSpecifications") List<McpServerFeatures.SyncToolSpecification> tools) {
        this.environment = environment;
        this.properties = properties;
        this.tools = tools;
    }

    @Bean(destroyMethod = "closeGracefully")
    @Conditional(StdioCondition.class)
    McpSyncServer stdioMcpServer() {
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        return build(McpServer.sync(transport));
    }

    @Bean(destroyMethod = "closeGracefully")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Conditional(StatefulHttpCondition.class)
    McpSyncServer streamableHttpMcpServer(McpStreamableServerTransportProvider transport) {
        return build(McpServer.sync(transport));
    }

    McpSyncServer build(McpServer.SyncSpecification<?> builder) {
        var capabilities = McpSchema.ServerCapabilities.builder();
        if (enabled("capabilities.tool")) {
            capabilities.tools(enabled("tool-change-notification"));
            builder.tools(tools);
        }
        if (enabled("capabilities.resource")) {
            capabilities.resources(false, enabled("resource-change-notification"));
        }
        if (enabled("capabilities.prompt")) {
            capabilities.prompts(enabled("prompt-change-notification"));
        }
        if (enabled("capabilities.completion")) {
            capabilities.completions();
        }
        return builder.serverInfo(serverInfo())
            .instructions(environment.getProperty(PREFIX + "instructions"))
            .requestTimeout(requestTimeout())
            .capabilities(capabilities.build())
            // Existing Java clients receive tool-owned validation errors and can omit optional values via null.
            .validateToolInputs(false)
            .build();
    }

    McpSchema.Implementation serverInfo() {
        String artifactVersion = LegacyMcpServerConfiguration.class.getPackage().getImplementationVersion();
        String version = environment.getProperty(PREFIX + "version",
            artifactVersion == null ? "development" : artifactVersion);
        return new McpSchema.Implementation(environment.getProperty(PREFIX + "name", properties.getMcp().getName()), version);
    }

    Duration requestTimeout() {
        return Binder.get(environment).bind(PREFIX + "request-timeout", Duration.class).orElse(Duration.ofSeconds(20));
    }

    boolean enabled(String property) {
        return environment.getProperty(PREFIX + property, Boolean.class, true);
    }

    static final class StatefulHttpCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return McpTransport.resolve(environment) == McpTransport.STREAMABLE_HTTP
                && LegacyHttpProtocol.resolve(environment) == LegacyHttpProtocol.STREAMABLE;
        }
    }

    static final class StdioCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            if (McpTransport.resolve(environment) != McpTransport.STDIO) {
                return false;
            }
            if (LegacyHttpProtocol.resolve(environment) == LegacyHttpProtocol.STATELESS) {
                throw new IllegalArgumentException(LegacyHttpProtocol.PROPERTY + "=STATELESS requires HTTP. "
                    + "Use STREAMABLE or SSE with stdio.");
            }
            return true;
        }
    }
}
