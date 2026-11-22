package io.policynim.mcp.transport;

import org.springframework.core.env.Environment;

import java.util.Locale;

enum LegacyHttpProtocol {
    STREAMABLE, SSE, STATELESS;

    static final String PROPERTY = "spring.ai.mcp.server.protocol";

    static LegacyHttpProtocol resolve(Environment environment) {
        String value = environment.getProperty(PROPERTY, STREAMABLE.name());
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(PROPERTY + " must be STREAMABLE, SSE or STATELESS.", exception);
        }
    }
}
