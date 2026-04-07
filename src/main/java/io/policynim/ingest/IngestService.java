package io.policynim.ingest;

import io.policynim.policy.chunk.PolicyChunk;
import io.policynim.policy.chunk.PolicyChunker;
import io.policynim.policy.model.ParsedPolicyDocument;

import java.util.ArrayList;
import java.util.List;

public class IngestService {

    private final PolicyCorpus corpus;
    private final PolicyChunker chunker;
    private final PolicyChunkStore chunkStore;

    public IngestService(PolicyCorpus corpus, PolicyChunker chunker) {
        this(corpus, chunker, PolicyChunkStore.noOp());
    }

    public IngestService(PolicyCorpus corpus, PolicyChunker chunker, PolicyChunkStore chunkStore) {
        this.corpus = corpus;
        this.chunker = chunker;
        this.chunkStore = chunkStore;
    }

    public IngestedPolicyCorpus ingest(IngestCommand command) {
        List<ParsedPolicyDocument> documents = corpus.load(command.corpusRoot());
        List<PolicyChunk> chunks = new ArrayList<>();
        for (ParsedPolicyDocument document : documents) {
            chunks.addAll(chunker.chunk(document));
        }
        IngestedPolicyCorpus ingestedCorpus = new IngestedPolicyCorpus(command.corpusRoot(), documents, chunks);
        chunkStore.replaceAll(ingestedCorpus);
        return ingestedCorpus;
    }
}
