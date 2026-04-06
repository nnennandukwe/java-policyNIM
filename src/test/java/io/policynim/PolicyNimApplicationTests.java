package io.policynim;

import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.transport.McpServerBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PolicyNimApplicationTests {

    @Autowired
    private PolicyNimProperties properties;

    @Autowired
    private McpServerBootstrap bootstrap;

    @Test
    void contextLoads() {
        assertThat(properties.getMcp().getName()).isEqualTo("PolicyNIM");
        assertThat(bootstrap.transport().configValue()).isEqualTo("streamable-http");
    }
}
