package io.policynim.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyNimPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PolicyNimApplicationTestConfiguration.class);

    @Test
    void bindsCustomMcpProperties() {
        contextRunner
            .withPropertyValues(
                "policynim.mcp.transport=stdio",
                "policynim.mcp.host=0.0.0.0",
                "policynim.mcp.port=9090",
                "policynim.mcp.streamable-http-path=/custom-mcp",
                "policynim.storage.table-name=bootstrap_chunks"
            )
            .run(context -> {
                PolicyNimProperties properties = context.getBean(PolicyNimProperties.class);
                assertThat(properties.getMcp().getTransport()).isEqualTo(McpTransport.STDIO);
                assertThat(properties.getMcp().getHost()).isEqualTo("0.0.0.0");
                assertThat(properties.getMcp().getPort()).isEqualTo(9090);
                assertThat(properties.getMcp().getStreamableHttpPath()).isEqualTo("/custom-mcp");
                assertThat(properties.getStorage().getTableName()).isEqualTo("bootstrap_chunks");
            });
    }
}
