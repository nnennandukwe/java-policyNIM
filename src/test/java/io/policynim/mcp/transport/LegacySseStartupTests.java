package io.policynim.mcp.transport;

import jakarta.servlet.Servlet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class LegacySseStartupTests {

    @Test
    void closesTheRegisteredTransportWhenServerCreationFails() {
        var installed = new AtomicReference<Servlet>();
        var settings = mock(LegacyMcpServerConfiguration.class);
        given(settings.build(any())).willThrow(new IllegalArgumentException("Invalid server settings"));
        new WebApplicationContextRunner()
            .withPropertyValues("spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.server.keep-alive-interval=1s")
            .withBean(LegacyMcpServerConfiguration.class, () -> settings)
            .withUserConfiguration(LegacyAlternateHttpConfiguration.class)
            .withInitializer(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof ServletRegistrationBean<?> registration
                        && registration.getUrlMappings().contains("/sse")) {
                        installed.set(registration.getServlet());
                    }
                    return bean;
                }
            }))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage("Invalid server settings");
                assertThat(installed.get()).isNotNull();
                var request = new MockHttpServletRequest("GET", "/sse");
                var response = new MockHttpServletResponse();
                installed.get().service(request, response);
                assertThat(response.getStatus()).isEqualTo(503);
            });
    }
}
