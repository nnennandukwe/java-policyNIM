package io.policynim;

import io.policynim.config.PolicyNimProperties;
import io.policynim.ingest.IngestService;
import io.policynim.mcp.transport.McpServerBootstrap;
import io.policynim.support.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
class PolicyNimApplicationTests {

    @Autowired
    private PolicyNimProperties properties;

    @Autowired
    private McpServerBootstrap bootstrap;

    @Autowired
    private IngestService ingestService;

    @Test
    void contextLoads() {
        assertThat(properties.getMcp().getName()).isEqualTo("PolicyNIM");
        assertThat(bootstrap.transport().configValue()).isEqualTo("streamable-http");
        assertThat(ingestService).isNotNull();
    }
}
