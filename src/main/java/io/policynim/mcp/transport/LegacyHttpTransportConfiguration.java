package io.policynim.mcp.transport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(LegacyMcpServerConfiguration.StatefulHttpCondition.class)
class LegacyHttpTransportConfiguration {

    @Bean(destroyMethod = "destroy")
    SessionHttpServlet legacySessionTransport(StreamableHttpTransportMarker marker, Environment environment) {
        var settings = Binder.get(environment);
        String prefix = "spring.ai.mcp.server.streamable-http.";
        return new SessionHttpServlet(marker.path(),
            settings.bind(prefix + "session-idle-timeout", Duration.class).orElse(Duration.ofMinutes(30)),
            settings.bind(prefix + "keep-alive-interval", Duration.class).orElse(null),
            settings.bind(prefix + "disallow-delete", Boolean.class).orElse(false));
    }

    @Bean
    ApplicationListener<ContextClosedEvent> closeMcpSessionsBeforeHttpDrain(
        ApplicationContext context, SessionHttpServlet transport
    ) {
        return event -> {
            if (event.getApplicationContext() == context) {
                transport.destroy();
            }
        };
    }

    @Bean
    ServletRegistrationBean<SessionHttpServlet> mcpServletRegistration(
        SessionHttpServlet transport, StreamableHttpTransportMarker marker
    ) {
        var servlet = new ServletRegistrationBean<>(transport, marker.path());
        servlet.setName("policyNimMcp");
        servlet.setAsyncSupported(true);
        return servlet;
    }
}
