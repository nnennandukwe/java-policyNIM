package io.policynim.preflight;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public record PreflightRequest(
    String task,
    String domain,
    @JsonProperty("top_k") int topK
) {

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    public PreflightRequest {
        Objects.requireNonNull(task, "task must not be null");
        if (task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
    }
}
