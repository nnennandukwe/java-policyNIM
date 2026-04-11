package io.policynim.provider;

import java.util.Objects;

public record PolicyGroundingEvidence(
    String chunkId,
    String path,
    String section,
    String lines,
    String text,
    String policyId,
    String title,
    String domain,
    double score
) {

    public PolicyGroundingEvidence {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(section, "section must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(policyId, "policyId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
    }
}
