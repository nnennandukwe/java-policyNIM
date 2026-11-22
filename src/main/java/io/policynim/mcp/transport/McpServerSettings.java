package io.policynim.mcp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.spec.McpSchema;
import io.policynim.config.PolicyNimProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Objects;

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

    String groundedInstructions() {
        // Public instructions at the pinned Python revision.
        return Objects.requireNonNullElse(instructions(),
            "Search policy sources or request citation-backed guidance before coding. "
                + "Treat insufficient_context as missing evidence; guidance does not execute changes.");
    }

    McpServer groundedModernServer(McpJsonMapper mapper, McpSyncToolRepository tools) {
        var identity = serverInfo();
        var server = McpServer.builder()
            .serverInfo(Implementation.builder(identity.name(), identity.version()).build())
            .instructions(groundedInstructions()).jsonMapper(mapper);
        if (enabled("capabilities.tool")) {
            server.feature(ToolsFeature.ofSync(tools));
        }
        return server.build();
    }
}
