package io.policynim.mcp.transport;

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
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PolicyNimProperties.class)
    static class TestPropertiesConfiguration {
    }
}
