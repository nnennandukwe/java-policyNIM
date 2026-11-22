package io.policynim.mcp.transport;

import io.modelcontextprotocol.spec.McpSchema;
import io.policynim.config.PolicyNimProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.time.Duration;

record McpServerSettings(Environment environment, PolicyNimProperties properties) {

    private static final String PREFIX = "spring.ai.mcp.server.";

    McpSchema.Implementation serverInfo() {
        String artifactVersion = McpServerSettings.class.getPackage().getImplementationVersion();
        String version = environment.getProperty(PREFIX + "version",
            artifactVersion == null ? "development" : artifactVersion);
        return new McpSchema.Implementation(environment.getProperty(PREFIX + "name", properties.getMcp().getName()), version);
    }

    Duration requestTimeout() {
        return Binder.get(environment).bind(PREFIX + "request-timeout", Duration.class).orElse(Duration.ofSeconds(20));
    }

    boolean enabled(String property) {
        return environment.getProperty(PREFIX + property, Boolean.class, true);
    }

    String instructions() {
        return environment.getProperty(PREFIX + "instructions");
    }
}
