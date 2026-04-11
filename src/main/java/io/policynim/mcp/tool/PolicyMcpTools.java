package io.policynim.mcp.tool;

import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.health.HealthCheckResponse;
import io.policynim.mcp.health.RuntimeReadinessService;
import io.policynim.mcp.telemetry.McpTelemetry;
import io.policynim.retrieval.SearchRequest;
import io.policynim.retrieval.SearchResult;
import io.policynim.retrieval.SearchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class PolicyMcpTools {

    private final SearchService searchService;
    private final PolicyNimProperties properties;
    private final RuntimeReadinessService readinessService;
    private final McpTelemetry telemetry;

    public PolicyMcpTools(
        SearchService searchService,
        PolicyNimProperties properties,
        RuntimeReadinessService readinessService,
        McpTelemetry telemetry
    ) {
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.readinessService = Objects.requireNonNull(readinessService, "readinessService must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    @Tool(name = "policy_search", description = "Search the local PolicyNIM policy corpus.")
    public SearchResult policySearch(
        @ToolParam(description = "The policy search query.") String query,
        @ToolParam(required = false, description = "Optional policy domain filter.") String domain,
        @ToolParam(required = false, description = "Maximum number of hits to return.") Integer topK
    ) {
        return telemetry.recordToolInvocation("policy_search", () -> {
            guardRuntimeReady("policy_search");
            return searchService.search(new SearchRequest(query, domain, resolveTopK(topK)));
        });
    }

    private int resolveTopK(Integer topK) {
        return topK != null ? topK : properties.getMcp().getDefaultTopK();
    }

    private void guardRuntimeReady(String toolName) {
        HealthCheckResponse readiness = readinessService.currentReadiness();
        if (!readiness.ready()) {
            throw new McpToolInvocationException(
                toolName + " is unavailable until PolicyNIM is ready. " + readiness.reason()
            );
        }
    }
}
