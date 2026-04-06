package io.policynim.ingest;

import io.policynim.policy.chunk.PolicyChunk;
import io.policynim.policy.model.ParsedPolicyDocument;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record IngestedPolicyCorpus(
    Path corpusRoot,
    List<ParsedPolicyDocument> documents,
    List<PolicyChunk> chunks
) {

    public IngestedPolicyCorpus {
        Objects.requireNonNull(corpusRoot, "corpusRoot must not be null");
        documents = List.copyOf(documents);
        chunks = List.copyOf(chunks);
    }
}
