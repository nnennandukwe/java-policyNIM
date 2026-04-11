package io.policynim;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyNimApplicationCommandModeTests {

    @Test
    void ingestHelpStartsWithoutServletWebSecurity() {
        String[] args = {"ingest", "--help"};

        try (ConfigurableApplicationContext context = PolicyNimApplication.application(args).run(args)) {
            assertThat(context.getEnvironment().getProperty("spring.ai.mcp.server.enabled")).isEqualTo("false");
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        }
    }
}
