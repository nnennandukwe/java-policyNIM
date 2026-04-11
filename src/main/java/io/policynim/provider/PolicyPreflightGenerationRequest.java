package io.policynim.provider;

import java.util.List;
import java.util.Objects;

public record PolicyPreflightGenerationRequest(
    String task,
    String domain,
    int topK,
    List<PolicyGroundingEvidence> evidence
) {

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    public PolicyPreflightGenerationRequest {
        Objects.requireNonNull(task, "task must not be null");
        if (task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
