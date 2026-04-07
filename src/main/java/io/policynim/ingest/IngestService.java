package io.policynim.ingest;

import io.policynim.policy.chunk.PolicyChunk;
import io.policynim.policy.chunk.PolicyChunker;
import io.policynim.policy.model.ParsedPolicyDocument;

import java.util.ArrayList;
import java.util.List;

public class IngestService {

    private final PolicyCorpus corpus;
    private final PolicyChunker chunker;

    public IngestService(PolicyCorpus corpus, PolicyChunker chunker) {
        this.corpus = corpus;
        this.chunker = chunker;
    }

    public IngestedPolicyCorpus ingest(IngestCommand command) {
        List<ParsedPolicyDocument> documents = corpus.load(command.corpusRoot());
        List<PolicyChunk> chunks = new ArrayList<>();
        for (ParsedPolicyDocument document : documents) {
            chunks.addAll(chunker.chunk(document));
        }
        return new IngestedPolicyCorpus(command.corpusRoot(), documents, chunks);
    }
}
