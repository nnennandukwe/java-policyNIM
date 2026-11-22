package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.policynim.config.McpContract;
import io.policynim.config.McpTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
class LegacyMcpServerConfiguration {

    private final McpServerSettings settings;
    private final List<McpServerFeatures.SyncToolSpecification> tools;

    LegacyMcpServerConfiguration(McpServerSettings settings,
        @Qualifier("policySyncToolSpecifications") List<McpServerFeatures.SyncToolSpecification> tools) {
        this.settings = settings;
        this.tools = tools;
    }

    @Bean(destroyMethod = "closeGracefully")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Conditional(StatefulHttpCondition.class)
    McpSyncServer streamableHttpMcpServer(McpStreamableServerTransportProvider transport) {
        return build(McpServer.sync(transport));
    }

    McpSyncServer build(McpServer.SyncSpecification<?> builder) {
        var capabilities = McpSchema.ServerCapabilities.builder();
        if (settings.enabled("capabilities.tool")) {
            capabilities.tools(settings.enabled("tool-change-notification"));
            builder.tools(tools);
        }
        if (settings.enabled("capabilities.resource")) {
            capabilities.resources(false, settings.enabled("resource-change-notification"));
        }
        if (settings.enabled("capabilities.prompt")) {
            capabilities.prompts(settings.enabled("prompt-change-notification"));
        }
        if (settings.enabled("capabilities.completion")) {
            capabilities.completions();
        }
        return builder.serverInfo(settings.serverInfo())
            .instructions(settings.instructions())
            .requestTimeout(settings.requestTimeout())
            .capabilities(capabilities.build())
            // Existing Java clients receive tool-owned validation errors and can omit optional values via null.
            .validateToolInputs(false)
            .build();
    }

    static final class StatefulHttpCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return McpContract.resolve(environment) == McpContract.JAVA_V1
                && McpTransport.resolve(environment) == McpTransport.STREAMABLE_HTTP
                && LegacyHttpProtocol.resolve(environment) == LegacyHttpProtocol.STREAMABLE;
        }
    }
}
