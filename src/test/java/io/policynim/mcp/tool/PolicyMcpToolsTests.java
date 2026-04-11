package io.policynim.mcp.tool;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.health.HealthCheckResponse;
import io.policynim.mcp.health.RuntimeReadinessService;
import io.policynim.mcp.telemetry.McpTelemetry;
import io.policynim.preflight.PreflightRequest;
import io.policynim.preflight.PreflightResult;
import io.policynim.preflight.PreflightService;
import io.policynim.retrieval.SearchRequest;
import io.policynim.retrieval.SearchResult;
import io.policynim.retrieval.SearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PolicyMcpToolsTests {

    @Test
    void blocksPolicySearchWhenRuntimeIsNotReadyAndRecordsTelemetry() {
        SearchService searchService = mock(SearchService.class);
        PreflightService preflightService = mock(PreflightService.class);
        RuntimeReadinessService readinessService = () -> new HealthCheckResponse(
            "error",
            false,
            "policy_chunks",
            0,
            null,
            "Run the ingest command before serving MCP traffic."
        );
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PolicyMcpTools tools = new PolicyMcpTools(
            searchService,
            preflightService,
            new PolicyNimProperties(),
            readinessService,
            new McpTelemetry(meterRegistry)
        );

        assertThatThrownBy(() -> tools.policySearch("pto", null, null))
            .isInstanceOf(McpToolInvocationException.class)
            .hasMessageContaining("policy_search is unavailable")
            .hasMessageContaining("Run the ingest command");

        verifyNoInteractions(searchService);
        verifyNoInteractions(preflightService);
        assertThat(meterRegistry.counter(
            McpTelemetry.TOOL_INVOCATIONS,
            "tool.name",
            "policy_search",
            "outcome",
            "error"
        ).count()).isEqualTo(1.0d);
    }

    @Test
    void blocksPolicyPreflightWhenRuntimeIsNotReadyAndRecordsTelemetry() {
        SearchService searchService = mock(SearchService.class);
        PreflightService preflightService = mock(PreflightService.class);
        RuntimeReadinessService readinessService = () -> new HealthCheckResponse(
            "error",
            false,
            "policy_chunks",
            0,
            null,
            "Run the ingest command before serving MCP traffic."
        );
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PolicyMcpTools tools = new PolicyMcpTools(
            searchService,
            preflightService,
            new PolicyNimProperties(),
            readinessService,
            new McpTelemetry(meterRegistry)
        );

        assertThatThrownBy(() -> tools.policyPreflight("refresh token cleanup", null, null))
            .isInstanceOf(McpToolInvocationException.class)
            .hasMessageContaining("policy_preflight is unavailable")
            .hasMessageContaining("Run the ingest command");

        verifyNoInteractions(searchService);
        verifyNoInteractions(preflightService);
        assertThat(meterRegistry.counter(
            McpTelemetry.TOOL_INVOCATIONS,
            "tool.name",
            "policy_preflight",
            "outcome",
            "error"
        ).count()).isEqualTo(1.0d);
    }

    @Test
    void delegatesPolicySearchWhenRuntimeIsReadyAndRecordsTelemetry() {
        SearchService searchService = mock(SearchService.class);
        RuntimeReadinessService readinessService = new ToolOnlyReadinessService();
        SearchResult searchResult = new SearchResult("pto", null, 5, List.of(), true);
        given(searchService.search(new SearchRequest("pto", null, 5))).willReturn(searchResult);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PolicyMcpTools tools = new PolicyMcpTools(
            searchService,
            mock(PreflightService.class),
            new PolicyNimProperties(),
            readinessService,
            new McpTelemetry(meterRegistry)
        );

        SearchResult result = tools.policySearch("pto", null, null);

        assertThat(result).isSameAs(searchResult);
        assertThat(meterRegistry.counter(
            McpTelemetry.TOOL_INVOCATIONS,
            "tool.name",
            "policy_search",
            "outcome",
            "success"
        ).count()).isEqualTo(1.0d);
    }

    @Test
    void delegatesPolicyPreflightWhenRuntimeIsReadyAndRecordsTelemetry() {
        SearchService searchService = mock(SearchService.class);
        PreflightService preflightService = mock(PreflightService.class);
        RuntimeReadinessService readinessService = new ToolOnlyReadinessService();
        PreflightResult preflightResult = PreflightResult.insufficientContext(
            new PreflightRequest("refresh token cleanup", "security", 5)
        );
        given(preflightService.preflight(new PreflightRequest("refresh token cleanup", "security", 5)))
            .willReturn(preflightResult);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PolicyMcpTools tools = new PolicyMcpTools(
            searchService,
            preflightService,
            new PolicyNimProperties(),
            readinessService,
            new McpTelemetry(meterRegistry)
        );

        PreflightResult result = tools.policyPreflight("refresh token cleanup", "security", null);

        assertThat(result).isSameAs(preflightResult);
        assertThat(meterRegistry.counter(
            McpTelemetry.TOOL_INVOCATIONS,
            "tool.name",
            "policy_preflight",
            "outcome",
            "success"
        ).count()).isEqualTo(1.0d);
    }

    private static final class ToolOnlyReadinessService implements RuntimeReadinessService {

        @Override
        public HealthCheckResponse currentReadiness() {
            throw new AssertionError("policy_search should use lightweight tool readiness");
        }

        @Override
        public HealthCheckResponse toolReadiness() {
            return new HealthCheckResponse(
                "ok",
                true,
                "policy_chunks",
                1,
                "https://example.com/mcp",
                "Policy chunk index is ready."
            );
        }
    }
}
