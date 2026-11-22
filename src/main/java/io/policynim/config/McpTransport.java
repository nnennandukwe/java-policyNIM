package io.policynim.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.Locale;

public enum McpTransport {
    STDIO("stdio"),
    STREAMABLE_HTTP("streamable-http");

    private final String configValue;

    McpTransport(String configValue) {
        this.configValue = configValue;
    }

    public static McpTransport resolve(Environment environment) {
        String springStdio = environment.getProperty("spring.ai.mcp.server.stdio");
        if (springStdio == null) {
            return Binder.get(environment)
                .bind("policynim.mcp.transport", McpTransport.class)
                .orElse(STREAMABLE_HTTP);
        }
        // Spring AI's WebMVC condition matches literal false, ignoring case. Other false
        // aliases bind as false in Boot but leave Spring AI using its stdio fallback.
        if ("false".equalsIgnoreCase(springStdio)) {
            return STREAMABLE_HTTP;
        }
        return switch (springStdio.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> STDIO;
            default -> throw new IllegalArgumentException(
                "Set spring.ai.mcp.server.stdio to true for stdio or false for HTTP."
            );
        };
    }

    public String configValue() {
        return configValue;
    }
}
