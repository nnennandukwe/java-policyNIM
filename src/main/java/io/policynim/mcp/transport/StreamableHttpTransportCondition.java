package io.policynim.mcp.transport;

import io.policynim.config.McpTransport;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class StreamableHttpTransportCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return McpTransport.resolve(context.getEnvironment()) == McpTransport.STREAMABLE_HTTP;
    }
}
