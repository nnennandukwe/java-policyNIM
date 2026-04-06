package io.policynim.policy.model;

import java.util.Objects;

public record ParsedPolicyDocument(
    String sourcePath,
    PolicyMetadata metadata,
    String body,
    int bodyStartLine
) {

    public ParsedPolicyDocument {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }
}
