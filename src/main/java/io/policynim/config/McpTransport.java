package io.policynim.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

public enum McpTransport {
    STDIO("stdio"),
    STREAMABLE_HTTP("streamable-http");

    private final String configValue;

    McpTransport(String configValue) {
        this.configValue = configValue;
    }

    public static McpTransport resolve(Environment environment) {
        Boolean springStdio = environment.getProperty("spring.ai.mcp.server.stdio", Boolean.class);
        if (springStdio != null) {
            return springStdio ? STDIO : STREAMABLE_HTTP;
        }
        return Binder.get(environment)
            .bind("policynim.mcp.transport", McpTransport.class)
            .orElse(STREAMABLE_HTTP);
    }

    public String configValue() {
        return configValue;
    }
}
