package io.policynim.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

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

        String transport = environment.getProperty("policynim.mcp.transport", McpTransport.STREAMABLE_HTTP.configValue());
        if (McpTransport.STDIO.configValue().equalsIgnoreCase(transport)) {
            putIfMissing(environment, derived, "spring.ai.mcp.server.stdio", "true");
        }
        else {
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
