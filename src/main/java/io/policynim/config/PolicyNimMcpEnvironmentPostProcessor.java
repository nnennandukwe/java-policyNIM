package io.policynim.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.LogFile;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public class PolicyNimMcpEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "policynimMcpDerivedSpringAi";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> derived = new LinkedHashMap<>();
        putIfMissing(environment, derived, "spring.ai.mcp.server.enabled", "true");
        putIfMissing(environment, derived, "spring.ai.mcp.server.annotation-scanner.enabled", "false");
        putIfMissing(
            environment,
            derived,
            "spring.ai.mcp.server.name",
            environment.getProperty("policynim.mcp.name")
        );

        McpTransport transport = McpTransport.resolve(environment);
        boolean enabled = environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class, true);
        if (enabled && McpContract.resolve(environment) == McpContract.GROUNDED_V1
            && transport == McpTransport.STREAMABLE_HTTP) {
            requireGroundedHttp(environment);
        }
        if (transport == McpTransport.STDIO) {
            putIfMissing(environment, derived, "spring.ai.mcp.server.stdio", "true");
            if (enabled) {
                configureStdioLaunch(environment, derived);
            }
        }
        else {
            putIfMissing(environment, derived, "server.address", environment.getProperty("policynim.mcp.host"));
            putIfMissing(environment, derived, "server.port", environment.getProperty("policynim.mcp.port"));
            putIfMissing(environment, derived, "spring.ai.mcp.server.stdio", "false");
            putIfMissing(environment, derived, "spring.ai.mcp.server.protocol", "STREAMABLE");
            putIfMissing(
                environment,
                derived,
                "spring.ai.mcp.server.streamable-http.mcp-endpoint",
                environment.getProperty("policynim.mcp.streamable-http-path")
            );
        }

        if (!derived.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, derived));
        }
    }

    private static void requireGroundedHttp(ConfigurableEnvironment environment) {
        if (!"STREAMABLE".equalsIgnoreCase(environment.getProperty("spring.ai.mcp.server.protocol", "STREAMABLE"))) {
            throw new IllegalArgumentException("policynim.mcp.contract=grounded-v1 requires "
                + "spring.ai.mcp.server.protocol=STREAMABLE.");
        }
        if (!"servlet".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", "servlet"))) {
            throw new IllegalArgumentException("policynim.mcp.contract=grounded-v1 requires "
                + "spring.main.web-application-type=servlet.");
        }
    }

    private static void configureStdioLaunch(ConfigurableEnvironment environment, Map<String, Object> derived) {
        putIfMissing(environment, derived, "spring.main.web-application-type", "none");
        putIfMissing(environment, derived, "spring.main.banner-mode", "off");
        if (environment.containsProperty("logging.config")) {
            return;
        }
        if (LogFile.get(environment) == null) {
            boolean structured = StringUtils.hasText(environment.getProperty("logging.structured.format.console"));
            derived.put(
                "logging.config",
                structured ? "classpath:logback-stdio-structured.xml" : "classpath:logback-stdio.xml"
            );
        }
        else {
            // Boot owns the rolling file appender. Its default console appender writes to stdout.
            putIfMissing(environment, derived, "logging.threshold.console", "OFF");
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static void putIfMissing(
        ConfigurableEnvironment environment,
        Map<String, Object> derived,
        String key,
        String value
    ) {
        if (value != null && !environment.containsProperty(key)) {
            derived.put(key, value);
        }
    }
}
