package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.policynim.config.PolicyNimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyMcpServerConfigurationTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withUserConfiguration(TestBeans.class);

    @Test
    void reportsDevelopmentVersionWhenRunningFromClasses() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(McpSyncServer.class);
            var server = context.getBean(McpSyncServer.class);
            assertThat(server.getServerInfo().name()).isEqualTo("PolicyNIM");
            assertThat(server.getServerInfo().version()).isEqualTo("development");
        });
    }

    @Test
    void usesAnExplicitSpringServerIdentity() {
        runner.withPropertyValues("spring.ai.mcp.server.name=private-policy",
            "spring.ai.mcp.server.version=2.4.1").run(context -> {
                var server = context.getBean(McpSyncServer.class);
                assertThat(server.getServerInfo().name()).isEqualTo("private-policy");
                assertThat(server.getServerInfo().version()).isEqualTo("2.4.1");
            });
    }

    @Test
    void leavesTheServerUnregisteredWhenDisabled() {
        runner.withPropertyValues("spring.ai.mcp.server.enabled=false").run(context ->
            assertThat(context).doesNotHaveBean(McpSyncServer.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PolicyNimProperties.class)
    @Import({McpBootstrapConfiguration.class, LegacyMcpServerConfiguration.class})
    static class TestBeans {

        @Bean
        List<McpServerFeatures.SyncToolSpecification> policySyncToolSpecifications() {
            return List.of();
        }
    }

}
