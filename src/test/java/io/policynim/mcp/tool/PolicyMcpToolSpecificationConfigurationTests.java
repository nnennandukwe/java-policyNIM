package io.policynim.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PolicyMcpToolSpecificationConfigurationTests {

    @Test
    void convertsReadinessFailuresIntoMcpErrorResultsWithRecoveryText() {
        PolicyMcpTools tools = mock(PolicyMcpTools.class);
        given(tools.policySearch("pto", null, null)).willThrow(new McpToolInvocationException(
            "policy_search is unavailable until PolicyNIM is ready. Run the ingest command."
        ));

        List<McpServerFeatures.SyncToolSpecification> specifications =
            new PolicyMcpToolSpecificationConfiguration().policySyncToolSpecifications(tools, new ObjectMapper());

        McpSchema.CallToolResult result = specifications.getFirst().call().apply(null, Map.of("query", "pto"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst()).isInstanceOf(McpSchema.TextContent.class);
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
            .contains("Run the ingest command");
        assertThat(result.structuredContent()).isEqualTo(Map.of(
            "error", "policy_search is unavailable until PolicyNIM is ready. Run the ingest command."
        ));
    }
}
