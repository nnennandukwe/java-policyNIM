package io.policynim.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PolicyMcpToolsIntegrationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @SuppressWarnings("unchecked")
    void registersPolicySearchOnTheStreamableServer() {
        List<McpServerFeatures.SyncToolSpecification> toolSpecifications =
            (List<McpServerFeatures.SyncToolSpecification>) applicationContext.getBean("policySyncToolSpecifications");

        assertThat(applicationContext.getBeansOfType(WebMvcStreamableServerTransportProvider.class)).hasSize(1);
        assertThat(applicationContext.containsBean("mcpHttpPlaceholderController")).isFalse();
        assertThat(toolSpecifications)
            .extracting(specification -> specification.tool().name())
            .containsExactly("policy_search");
    }
}
