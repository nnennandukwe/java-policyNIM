package io.policynim.mcp.transport;

import io.policynim.config.McpContract;
import io.policynim.config.McpTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@Conditional(GroundedHttpCondition.class)
@interface ConditionalOnGroundedHttp {
}

final class GroundedHttpCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        return McpContract.resolve(environment) == McpContract.GROUNDED_V1
            && McpTransport.resolve(environment) == McpTransport.STREAMABLE_HTTP;
    }
}
