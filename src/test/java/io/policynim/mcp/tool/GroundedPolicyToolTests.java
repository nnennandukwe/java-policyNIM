package io.policynim.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.policynim.preflight.PreflightRequest;
import io.policynim.preflight.PreflightResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig({PolicyMcpToolSpecificationConfiguration.class, GroundedPolicyToolTests.Services.class})
@TestPropertySource(properties = "policynim.mcp.contract=grounded-v1")
class GroundedPolicyToolTests {

    @Autowired
    @Qualifier("groundedModernTools")
    private McpSyncToolRepository modern;

    @Autowired
    @Qualifier("groundedLegacyTools")
    private List<McpStatelessServerFeatures.SyncToolSpecification> legacy;

    @Autowired
    private PolicyMcpTools tools;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void bothProtocolsAdvertiseTheTwoCurrentToolDefinitions() {
        var page = modern.list(null, null);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.tools()).extracting(McpSchema.Tool::name).containsExactly("policy_search", "policy_preflight");
        assertThat(modern.find(null, "policy_compile")).isNull();
        JsonNode modernDefinitions = mapper.valueToTree(page.tools());
        JsonNode legacyDefinitions = mapper.valueToTree(
            legacy.stream().map(McpStatelessServerFeatures.SyncToolSpecification::tool).toList());
        assertThat(legacyDefinitions).isEqualTo(modernDefinitions);
        assertThat(modernDefinitions.at("/0/inputSchema/required/0").asText()).isEqualTo("query");
        assertThat(modernDefinitions.at("/1/inputSchema/required/0").asText()).isEqualTo("task");
        assertThat(modernDefinitions.at("/0/inputSchema/properties/topK/maximum").asInt()).isEqualTo(20);
    }

    @Test
    void preservesPreflightRefusalsAsSuccessfulCompletions() throws Exception {
        var preflight = new PreflightRequest("refresh cleanup", "security", 3);
        given(tools.policyPreflight(preflight.task(), preflight.domain(), preflight.topK()))
            .willReturn(PreflightResult.insufficientContext(preflight));
        for (JsonNode response : callBoth("policy_preflight",
            Map.of("task", preflight.task(), "domain", preflight.domain(), "topK", 3))) {
            assertThat(response.required("isError").asBoolean()).isFalse();
            assertThat(response.at("/structuredContent/insufficient_context").asBoolean()).isTrue();
            assertThat(mapper.readTree(response.at("/content/0/text").asText())).isEqualTo(response.required("structuredContent"));
        }
        verify(tools, times(2)).policyPreflight("refresh cleanup", "security", 3);
    }

    @Test
    void preservesReadinessErrorsInTextAndStructuredContent() {
        String message = "policy_search is unavailable until PolicyNIM is ready.";
        given(tools.policySearch("policy", null, null)).willThrow(new McpToolInvocationException(message));
        for (JsonNode response : callBoth("policy_search", Map.of("query", "policy"))) {
            assertThat(response.required("isError").asBoolean()).isTrue();
            assertThat(response.at("/structuredContent/error").asText()).isEqualTo(message);
            assertThat(response.at("/content/0/text").asText()).isEqualTo(message);
        }
        verify(tools, times(2)).policySearch("policy", null, null);
    }

    private List<JsonNode> callBoth(String name, Map<String, Object> arguments) {
        var request = McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
        var reply = (McpSyncResponse.Result<?>) modern.find(null, name).handler().call(null, request);
        JsonNode modernResult = mapper.valueToTree(reply.result());
        assertThat(modernResult.required("resultType").textValue()).isEqualTo("complete");

        var legacyRequest = io.modelcontextprotocol.spec.McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
        var tool = legacy.stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow();
        JsonNode legacyResult = mapper.valueToTree(tool.callHandler().apply(McpTransportContext.EMPTY, legacyRequest));
        assertThat(legacyResult.has("resultType")).isFalse();
        return List.of(modernResult, legacyResult);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Services {
        @Bean
        PolicyMcpTools policyMcpTools() {
            return mock(PolicyMcpTools.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
