package io.policynim.mcp.transport;

import io.policynim.config.McpTransport;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class StreamableHttpTransportCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
            .bind("policynim.mcp.transport", McpTransport.class)
            .orElse(McpTransport.STREAMABLE_HTTP) == McpTransport.STREAMABLE_HTTP;
    }
}
