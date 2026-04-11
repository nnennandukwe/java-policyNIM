package io.policynim.config;

import io.policynim.PolicyNimApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyNimMcpEnvironmentPostProcessorTests {

    @Test
    void derivesStreamableHttpRuntimeSettingsFromPolicyNimMcpProperties() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "policynim.mcp.name", "PolicyNIM",
                    "policynim.mcp.transport", "streamable-http",
                    "policynim.mcp.host", "0.0.0.0",
                    "policynim.mcp.port", "9090",
                    "policynim.mcp.streamable-http-path", "/custom-mcp"
                )
            )
        );

        new PolicyNimMcpEnvironmentPostProcessor()
            .postProcessEnvironment(environment, new SpringApplication(PolicyNimApplication.class));

        assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
        assertThat(environment.getProperty("server.port")).isEqualTo("9090");
        assertThat(environment.getProperty("spring.ai.mcp.server.name")).isEqualTo("PolicyNIM");
        assertThat(environment.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STREAMABLE");
        assertThat(environment.getProperty("spring.ai.mcp.server.stdio")).isEqualTo("false");
        assertThat(environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint"))
            .isEqualTo("/custom-mcp");
    }

    @Test
    void doesNotOverrideExplicitSpringAiTransportSettings() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "policynim.mcp.transport", "stdio",
                    "spring.ai.mcp.server.stdio", "false",
                    "spring.ai.mcp.server.protocol", "STREAMABLE"
                )
            )
        );

        new PolicyNimMcpEnvironmentPostProcessor()
            .postProcessEnvironment(environment, new SpringApplication(PolicyNimApplication.class));

        assertThat(environment.getProperty("spring.ai.mcp.server.stdio")).isEqualTo("false");
        assertThat(environment.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STREAMABLE");
    }

    @Test
    void doesNotOverrideExplicitSpringBootServerBindSettings() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "policynim.mcp.transport", "streamable-http",
                    "policynim.mcp.host", "127.0.0.1",
                    "policynim.mcp.port", "8080",
                    "server.address", "0.0.0.0",
                    "server.port", "9090"
                )
            )
        );

        new PolicyNimMcpEnvironmentPostProcessor()
            .postProcessEnvironment(environment, new SpringApplication(PolicyNimApplication.class));

        assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
        assertThat(environment.getProperty("server.port")).isEqualTo("9090");
    }
}
