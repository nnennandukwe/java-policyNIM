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
                "policynim.mcp.public-base-url=https://example.com/platform/",
                "policynim.storage.mode=jdbc",
                "policynim.storage.table-name=bootstrap_chunks"
            )
            .run(context -> {
                PolicyNimProperties properties = context.getBean(PolicyNimProperties.class);
                assertThat(properties.getMcp().getTransport()).isEqualTo(McpTransport.STDIO);
                assertThat(properties.getMcp().getHost()).isEqualTo("0.0.0.0");
                assertThat(properties.getMcp().getPort()).isEqualTo(9090);
                assertThat(properties.getMcp().getStreamableHttpPath()).isEqualTo("/custom-mcp");
                assertThat(properties.getMcp().mcpUrl()).isEqualTo("https://example.com/platform/custom-mcp");
                assertThat(properties.getStorage().getMode()).isEqualTo(PolicyNimProperties.StorageMode.JDBC);
                assertThat(properties.getStorage().getTableName()).isEqualTo("bootstrap_chunks");
            });
    }

    @Test
    void rejectsStreamableHttpPathsWithoutLeadingSlash() {
        contextRunner
            .withPropertyValues("policynim.mcp.streamable-http-path=custom-mcp")
            .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void rejectsUnsafeStorageTableNames() {
        contextRunner
            .withPropertyValues("policynim.storage.table-name=policy-chunks")
            .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }
}
