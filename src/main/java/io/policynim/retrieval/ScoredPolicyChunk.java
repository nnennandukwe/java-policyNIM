package io.policynim.retrieval;

import io.policynim.policy.model.PolicyMetadata;

public record ScoredPolicyChunk(
    String chunkId,
    String path,
    String section,
    String lines,
    String text,
    PolicyMetadata policy,
    double score
) {
}
