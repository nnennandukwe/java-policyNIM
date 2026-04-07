package io.policynim.retrieval;

import java.util.Objects;

public record SearchRequest(String query, String domain, int topK) {

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    public SearchRequest {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
    }
}
