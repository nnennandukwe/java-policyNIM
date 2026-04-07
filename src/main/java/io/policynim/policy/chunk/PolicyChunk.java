package io.policynim.policy.chunk;

import io.policynim.policy.model.PolicyMetadata;

public record PolicyChunk(
    String chunkId,
    String path,
    String section,
    String lines,
    String text,
    PolicyMetadata policy
) {
}
