package io.policynim.config;

import io.policynim.PolicyNimApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyNimMcpEnvironmentPostProcessorTests {

    @Test
    void configuresAStdioLaunchWithoutAWebServerOrConsoleOutputOnStdout() {
        MockEnvironment environment = new MockEnvironment().withProperty("policynim.mcp.transport", "stdio");

        postProcess(environment);

        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("off");
        assertThat(environment.getProperty("logging.config")).isEqualTo("classpath:logback-stdio.xml");
        assertThat(environment.getProperty("spring.ai.mcp.server.stdio")).isEqualTo("true");
    }

    @Test
    void explicitSpringStdioSwitchSelectsTheLaunchDefaults() {
        MockEnvironment stdio = new MockEnvironment()
            .withProperty("policynim.mcp.transport", "streamable-http")
            .withProperty("spring.ai.mcp.server.stdio", "true");
        MockEnvironment http = new MockEnvironment()
            .withProperty("policynim.mcp.transport", "stdio")
            .withProperty("policynim.mcp.host", "127.0.0.1")
            .withProperty("policynim.mcp.streamable-http-path", "/custom")
            .withProperty("spring.ai.mcp.server.stdio", "false");

        postProcess(stdio);
        postProcess(http);

        assertThat(stdio.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(http.getProperty("spring.main.web-application-type")).isNull();
        assertThat(http.getProperty("logging.config")).isNull();
        assertThat(http.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(http.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint")).isEqualTo("/custom");
    }

    @ParameterizedTest
    @ValueSource(strings = {"logging.file.name", "logging.file.path"})
    void preservesBootFileLoggingAndSilencesItsStdoutAppender(String destinationProperty) {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("policynim.mcp.transport", "stdio")
            .withProperty(destinationProperty, "logs");

        postProcess(environment);

        assertThat(environment.getProperty("logging.config")).isNull();
        assertThat(environment.getProperty("logging.threshold.console")).isEqualTo("OFF");
        assertThat(environment.getProperty(destinationProperty)).isEqualTo("logs");
    }

    @Test
    void disabledMcpServerKeepsTheCommandLaunchSettings() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("policynim.mcp.transport", "stdio")
            .withProperty("spring.ai.mcp.server.enabled", "false");

        postProcess(environment);

        assertThat(environment.getProperty("spring.main.web-application-type")).isNull();
        assertThat(environment.getProperty("spring.main.banner-mode")).isNull();
        assertThat(environment.getProperty("logging.config")).isNull();
    }

    @Test
    void explicitApplicationAndLoggingConfigurationKeepPriorityInStdioMode() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("policynim.mcp.transport", "stdio")
            .withProperty("spring.main.web-application-type", "servlet")
            .withProperty("spring.main.banner-mode", "log")
            .withProperty("logging.config", "file:operator-logging.xml")
            .withProperty("logging.file.name", "operator.log");

        postProcess(environment);

        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("servlet");
        assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("log");
        assertThat(environment.getProperty("logging.config")).isEqualTo("file:operator-logging.xml");
        assertThat(environment.getProperty("logging.threshold.console")).isNull();
    }

    private static void postProcess(ConfigurableEnvironment environment) {
        new PolicyNimMcpEnvironmentPostProcessor()
            .postProcessEnvironment(environment, new SpringApplication(PolicyNimApplication.class));
    }

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
