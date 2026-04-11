package io.policynim.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @Test
    void registersPolicyPreflightWithTaskArguments() {
        PolicyMcpTools tools = mock(PolicyMcpTools.class);

        List<McpServerFeatures.SyncToolSpecification> specifications =
            new PolicyMcpToolSpecificationConfiguration().policySyncToolSpecifications(tools, new ObjectMapper());

        Map<String, McpServerFeatures.SyncToolSpecification> byName = specifications.stream()
            .collect(Collectors.toMap(specification -> specification.tool().name(), specification -> specification));

        assertThat(byName.keySet()).containsExactlyInAnyOrder("policy_search", "policy_preflight");
        byName.get("policy_preflight").call().apply(null, Map.of(
            "task", "refresh token cleanup",
            "domain", "security",
            "topK", 3
        ));
        org.mockito.Mockito.verify(tools).policyPreflight("refresh token cleanup", "security", 3);
    }

    @Test
    void convertsPreflightRuntimeFailuresIntoSafeMcpErrorResults() {
        PolicyMcpTools tools = mock(PolicyMcpTools.class);
        given(tools.policyPreflight("refresh token cleanup", null, null)).willThrow(
            new IllegalStateException("NVIDIA preflight response contained: {\"sensitive\":\"payload\"}")
        );

        List<McpServerFeatures.SyncToolSpecification> specifications =
            new PolicyMcpToolSpecificationConfiguration().policySyncToolSpecifications(tools, new ObjectMapper());
        Map<String, McpServerFeatures.SyncToolSpecification> byName = specifications.stream()
            .collect(Collectors.toMap(specification -> specification.tool().name(), specification -> specification));

        McpSchema.CallToolResult result = byName.get("policy_preflight").call().apply(
            null,
            Map.of("task", "refresh token cleanup")
        );

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
            .isEqualTo("PolicyNIM tool invocation failed. See server logs for details.")
            .doesNotContain("sensitive");
        assertThat(result.structuredContent()).isEqualTo(Map.of(
            "error", "PolicyNIM tool invocation failed. See server logs for details."
        ));
    }
}
