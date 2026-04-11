package io.policynim.retrieval;

import java.util.List;

public record SearchResult(
    String query,
    String domain,
    int topK,
    List<ScoredPolicyChunk> hits,
    boolean insufficientContext
) {

    public SearchResult {
        hits = List.copyOf(hits);
    }
}
