package io.policynim;

import io.policynim.config.PolicyNimProperties;
import io.policynim.ingest.IngestCommandLine;
import io.policynim.ingest.IngestService;
import io.policynim.mcp.transport.McpServerBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
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
        assertThat(properties.getStorage().getMode()).isEqualTo(PolicyNimProperties.StorageMode.NOOP);
        assertThat(bootstrap.transport().configValue()).isEqualTo("streamable-http");
        assertThat(ingestService).isNotNull();
    }

    @Test
    void ingestCommandRunsWithoutStartingTheHostedWebServer() {
        assertThat(PolicyNimApplication.application(new String[] {
            "ingest",
            "--corpus-root=/tmp/policies",
            "--policynim.storage.mode=jdbc"
        }).getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
        assertThat(IngestCommandLine.USAGE).contains("ingest --corpus-root=<path>");
    }
}
