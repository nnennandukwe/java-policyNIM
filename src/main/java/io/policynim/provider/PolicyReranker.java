package io.policynim.provider;

import java.util.List;

@FunctionalInterface
public interface PolicyReranker {

    List<RerankResult> rerank(String query, List<String> passages);

    static PolicyReranker noOp() {
        return (query, passages) -> List.of();
    }
}
