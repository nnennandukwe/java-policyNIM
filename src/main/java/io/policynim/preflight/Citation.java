package io.policynim.preflight;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public record Citation(
    @JsonProperty("policy_id") String policyId,
    String title,
    String path,
    String section,
    String lines,
    @JsonProperty("chunk_id") String chunkId
) {

    public Citation {
        Objects.requireNonNull(policyId, "policyId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(section, "section must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(chunkId, "chunkId must not be null");
    }
}
