package io.policynim.mcp.transport;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.policynim.config.McpContract;
import io.policynim.config.McpTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@Conditional(StdioMcpConfiguration.StdioCondition.class)
class StdioMcpConfiguration {

    @Bean(destroyMethod = "closeGracefully")
    @ConditionalOnProperty(name = McpContract.PROPERTY, havingValue = "java-v1", matchIfMissing = true)
    McpSyncServer stdioMcpServer(LegacyMcpServerConfiguration legacy) {
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        return legacy.build(McpServer.sync(transport));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = McpContract.PROPERTY, havingValue = "grounded-v1")
    GroundedStdioRuntime groundedStdioRuntime(McpServerSettings settings,
        @Qualifier("policySyncToolSpecifications") List<McpServerFeatures.SyncToolSpecification> legacyTools,
        @Qualifier("groundedModernTools") McpSyncToolRepository modernTools) {
        return new GroundedStdioRuntime(System.in, System.out, settings, legacyTools, modernTools);
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
