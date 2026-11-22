package io.policynim.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.policynim.preflight.PreflightRequest;
import io.policynim.preflight.PreflightResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PolicyMcpToolSpecificationConfigurationTests {

    @ParameterizedTest
    @CsvSource({"policy_search, query", "policy_preflight, task"})
    void reportsTheMissingRequiredFieldWhenArgumentsAreOmitted(String toolName, String requiredField) {
        var specifications = new PolicyMcpToolSpecificationConfiguration()
            .policySyncToolSpecifications(mock(PolicyMcpTools.class), new ObjectMapper());
        var tool = specifications.stream().filter(spec -> spec.tool().name().equals(toolName)).findFirst().orElseThrow();

        var result = tool.callHandler().apply(null, McpSchema.CallToolRequest.builder(toolName).build());

        String message = requiredField + " must not be blank";
        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent()).isEqualTo(Map.of("error", message));
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(McpSchema.TextContent.class,
            text -> assertThat(text.text()).isEqualTo(message));
    }

    @Test
    void convertsReadinessFailuresIntoMcpErrorResultsWithRecoveryText() {
        PolicyMcpTools tools = mock(PolicyMcpTools.class);
        given(tools.policySearch("pto", null, null)).willThrow(new McpToolInvocationException(
            "policy_search is unavailable until PolicyNIM is ready. Run the ingest command."
        ));

        List<McpServerFeatures.SyncToolSpecification> specifications =
            new PolicyMcpToolSpecificationConfiguration().policySyncToolSpecifications(tools, new ObjectMapper());

        McpSchema.CallToolResult result = specifications.getFirst().callHandler().apply(
            null,
            McpSchema.CallToolRequest.builder("policy_search").arguments(Map.of("query", "pto")).build()
        );

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
    void returnsPreflightRefusalsAsSuccessfulStructuredAndTextResults() throws JsonProcessingException {
        PolicyMcpTools tools = mock(PolicyMcpTools.class);
        ObjectMapper mapper = new ObjectMapper();
        PreflightRequest preflight = new PreflightRequest("refresh token cleanup", "security", 3);
        given(tools.policyPreflight(preflight.task(), preflight.domain(), preflight.topK()))
            .willReturn(PreflightResult.insufficientContext(preflight));

        List<McpServerFeatures.SyncToolSpecification> specifications =
            new PolicyMcpToolSpecificationConfiguration().policySyncToolSpecifications(tools, mapper);

        Map<String, McpServerFeatures.SyncToolSpecification> byName = specifications.stream()
            .collect(Collectors.toMap(specification -> specification.tool().name(), specification -> specification));

        assertThat(byName.keySet()).containsExactlyInAnyOrder("policy_search", "policy_preflight");
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder("policy_preflight")
            .arguments(Map.of("task", "refresh token cleanup", "domain", "security", "topK", 3))
            .build();
        McpSchema.CallToolResult result = byName.get("policy_preflight").callHandler().apply(null, request);

        verify(tools).policyPreflight("refresh token cleanup", "security", 3);
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        JsonNode structured = mapper.valueToTree(result.structuredContent());
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertThat(structured.get("insufficient_context").booleanValue()).isTrue();
        assertThat(mapper.readTree(text)).isEqualTo(structured);
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

        McpSchema.CallToolResult result = byName.get("policy_preflight").callHandler().apply(
            null,
            McpSchema.CallToolRequest.builder("policy_preflight")
                .arguments(Map.of("task", "refresh token cleanup"))
                .build()
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
