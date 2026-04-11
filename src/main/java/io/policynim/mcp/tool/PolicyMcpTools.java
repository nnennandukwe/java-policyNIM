package io.policynim.mcp.tool;

import io.policynim.config.PolicyNimProperties;
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

    public PolicyMcpTools(
        SearchService searchService,
        PolicyNimProperties properties
    ) {
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Tool(name = "policy_search", description = "Search the local PolicyNIM policy corpus.")
    public SearchResult policySearch(
        @ToolParam(description = "The policy search query.") String query,
        @ToolParam(required = false, description = "Optional policy domain filter.") String domain,
        @ToolParam(required = false, description = "Maximum number of hits to return.") Integer topK
    ) {
        return searchService.search(new SearchRequest(query, domain, resolveTopK(topK)));
    }

    private int resolveTopK(Integer topK) {
        return topK != null ? topK : properties.getMcp().getDefaultTopK();
    }
}
