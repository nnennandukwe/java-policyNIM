package io.policynim.mcp.transport;

import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThat;

class McpBootstrapConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestPropertiesConfiguration.class, McpBootstrapConfiguration.class);

    @Test
    void selectsStreamableHttpBootstrapByDefault() {
        contextRunner.run(context -> {
            McpServerBootstrap bootstrap = context.getBean(McpServerBootstrap.class);
            assertThat(bootstrap.transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
            assertThat(context).hasSingleBean(StreamableHttpTransportMarker.class);
        });
    }

    @Test
    void selectsStdioBootstrapWhenConfigured() {
        contextRunner
            .withPropertyValues("policynim.mcp.transport=stdio")
            .run(context -> {
                McpServerBootstrap bootstrap = context.getBean(McpServerBootstrap.class);
                assertThat(bootstrap.transport()).isEqualTo(McpTransport.STDIO);
                assertThat(bootstrap.description()).contains("stdio");
                assertThat(context).doesNotHaveBean(StreamableHttpTransportMarker.class);
            });
    }

    @Test
    void reportsTheTransportAndEndpointSelectedByExplicitSpringProperties() {
        contextRunner.withPropertyValues(
            "policynim.mcp.transport=stdio",
            "spring.ai.mcp.server.stdio=false",
            "spring.ai.mcp.server.streamable-http.mcp-endpoint=/policies"
        ).run(context -> {
            McpServerBootstrap bootstrap = context.getBean(McpServerBootstrap.class);
            assertThat(bootstrap.transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
            assertThat(bootstrap.description()).contains("/policies");
            assertThat(context.getBean(StreamableHttpTransportMarker.class).path()).isEqualTo("/policies");
        });
    }

    @Test
    void explicitSpringStdioOmitsTheHttpMarker() {
        contextRunner.withPropertyValues("spring.ai.mcp.server.stdio=true").run(context -> {
            assertThat(context.getBean(McpServerBootstrap.class).transport()).isEqualTo(McpTransport.STDIO);
            assertThat(context).doesNotHaveBean(StreamableHttpTransportMarker.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PolicyNimProperties.class)
    static class TestPropertiesConfiguration {
    }
}
