package io.policynim.retrieval;

import io.policynim.provider.EmbeddingInputType;
import io.policynim.provider.PolicyEmbeddingModel;
import io.policynim.provider.PolicyReranker;
import io.policynim.provider.RerankResult;
import io.policynim.policy.model.PolicyMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTests {

    @Test
    void reranksSemanticCandidatesWhenProviderModelsAreAvailable() {
        StubPolicyChunkReadStore readStore = new StubPolicyChunkReadStore(
            List.of(
                scoredChunk(
                    "BACKEND-LOG-001:logging__rules",
                    "Logging > Rules",
                    "Log with request ids.",
                    "BACKEND-LOG-001",
                    "Logging",
                    16.0
                ),
                scoredChunk(
                    "BACKEND-TRACE-001:tracing",
                    "Tracing",
                    "Trace downstream calls.",
                    "BACKEND-TRACE-001",
                    "Tracing",
                    12.0
                )
            )
        );
        StubPolicyEmbeddingModel embeddingModel = new StubPolicyEmbeddingModel(new float[] {0.5f, -0.25f, 1.75f});
        StubPolicyReranker reranker = new StubPolicyReranker(
            List.of(
                new RerankResult(1, 9.5d),
                new RerankResult(0, 2.0d)
            )
        );
        SearchService service = new SearchService(readStore, embeddingModel, reranker);

        SearchResult result = service.search(new SearchRequest("request ids", "backend", 2));

        assertThat(embeddingModel.lastInputType).isEqualTo(EmbeddingInputType.QUERY);
        assertThat(embeddingModel.lastInputs).containsExactly("request ids");
        assertThat(readStore.lastEmbedding).containsExactly(0.5f, -0.25f, 1.75f);
        assertThat(readStore.lastDomain).isEqualTo("backend");
        assertThat(readStore.lastLimit).isEqualTo(2);
        assertThat(reranker.lastQuery).isEqualTo("request ids");
        assertThat(reranker.lastPassages).containsExactly(
            "Log with request ids.",
            "Trace downstream calls."
        );
        assertThat(result.query()).isEqualTo("request ids");
        assertThat(result.domain()).isEqualTo("backend");
        assertThat(result.topK()).isEqualTo(2);
        assertThat(result.insufficientContext()).isFalse();
        assertThat(result.hits()).extracting(ScoredPolicyChunk::chunkId).containsExactly(
            "BACKEND-TRACE-001:tracing",
            "BACKEND-LOG-001:logging__rules"
        );
    }

    @Test
    void reportsInsufficientContextWhenNoHitsMatch() {
        SearchService service = new SearchService(new StubPolicyChunkReadStore(List.of()));

        SearchResult result = service.search(new SearchRequest("rotate secrets", null, 3));

        assertThat(result.hits()).isEmpty();
        assertThat(result.insufficientContext()).isTrue();
    }

    @Test
    void rerankAppendsRemainingCandidatesWhenRerankerReturnsPartialResults() {
        StubPolicyChunkReadStore readStore = new StubPolicyChunkReadStore(
            List.of(
                scoredChunk("POLICY-001:first", "First", "First match.", "POLICY-001", "First", 10.0),
                scoredChunk("POLICY-002:second", "Second", "Second match.", "POLICY-002", "Second", 9.0),
                scoredChunk("POLICY-003:third", "Third", "Third match.", "POLICY-003", "Third", 8.0)
            )
        );
        StubPolicyReranker reranker = new StubPolicyReranker(List.of(new RerankResult(1, 99.0d)));
        SearchService service = new SearchService(readStore, PolicyEmbeddingModel.noOp(), reranker);

        SearchResult result = service.search(new SearchRequest("match", "backend", 3));

        assertThat(result.insufficientContext()).isFalse();
        assertThat(result.hits()).extracting(ScoredPolicyChunk::chunkId).containsExactly(
            "POLICY-002:second",
            "POLICY-001:first",
            "POLICY-003:third"
        );
        assertThat(result.hits()).extracting(ScoredPolicyChunk::score).containsExactly(99.0d, 10.0d, 8.0d);
    }

    @Test
    void rerankFallsBackToOriginalOrderWhenRerankerReturnsOnlyInvalidIndexes() {
        StubPolicyChunkReadStore readStore = new StubPolicyChunkReadStore(
            List.of(
                scoredChunk("POLICY-001:first", "First", "First match.", "POLICY-001", "First", 10.0),
                scoredChunk("POLICY-002:second", "Second", "Second match.", "POLICY-002", "Second", 9.0)
            )
        );
        StubPolicyReranker reranker = new StubPolicyReranker(List.of(new RerankResult(7, 99.0d)));
        SearchService service = new SearchService(readStore, PolicyEmbeddingModel.noOp(), reranker);

        SearchResult result = service.search(new SearchRequest("match", "backend", 2));

        assertThat(result.insufficientContext()).isFalse();
        assertThat(result.hits()).extracting(ScoredPolicyChunk::chunkId).containsExactly(
            "POLICY-001:first",
            "POLICY-002:second"
        );
    }

    @Test
    void skipsEmbeddingWhenReadStoreHasNoRows() {
        StubPolicyChunkReadStore readStore = new StubPolicyChunkReadStore(List.of());
        StubPolicyEmbeddingModel embeddingModel = new StubPolicyEmbeddingModel(new float[] {0.5f});
        SearchService service = new SearchService(readStore, embeddingModel, PolicyReranker.noOp());

        SearchResult result = service.search(new SearchRequest("request ids", "backend", 2));

        assertThat(result.hits()).isEmpty();
        assertThat(embeddingModel.lastInputs).isNull();
        assertThat(readStore.lastEmbedding).isNull();
    }

    private static ScoredPolicyChunk scoredChunk(
        String chunkId,
        String section,
        String text,
        String policyId,
        String title,
        double score
    ) {
        return new ScoredPolicyChunk(
            chunkId,
            "policies/backend/" + title.toLowerCase() + ".md",
            section,
            "10-14",
            text,
            new PolicyMetadata(policyId, title, "guidance", "backend", List.of("observability"), List.of()),
            score
        );
    }

    private static final class StubPolicyChunkReadStore implements PolicyChunkReadStore {

        private final List<ScoredPolicyChunk> hits;
        private float[] lastEmbedding;
        private String lastDomain;
        private int lastLimit;

        private StubPolicyChunkReadStore(List<ScoredPolicyChunk> hits) {
            this.hits = hits;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public long rowCount() {
            return hits.size();
        }

        @Override
        public List<ScoredPolicyChunk> search(String query, String domain, int limit) {
            this.lastDomain = domain;
            this.lastLimit = limit;
            return hits.stream().limit(limit).toList();
        }

        @Override
        public List<ScoredPolicyChunk> searchByEmbedding(float[] embedding, String domain, int limit) {
            this.lastEmbedding = embedding;
            this.lastDomain = domain;
            this.lastLimit = limit;
            return hits.stream().limit(limit).toList();
        }
    }

    private static final class StubPolicyEmbeddingModel implements PolicyEmbeddingModel {

        private final float[] embedding;
        private List<String> lastInputs;
        private EmbeddingInputType lastInputType;

        private StubPolicyEmbeddingModel(float[] embedding) {
            this.embedding = embedding;
        }

        @Override
        public List<float[]> embed(List<String> inputs, EmbeddingInputType inputType) {
            this.lastInputs = inputs;
            this.lastInputType = inputType;
            return List.of(embedding);
        }
    }

    private static final class StubPolicyReranker implements PolicyReranker {

        private final List<RerankResult> results;
        private String lastQuery;
        private List<String> lastPassages;

        private StubPolicyReranker(List<RerankResult> results) {
            this.results = results;
        }

        @Override
        public List<RerankResult> rerank(String query, List<String> passages) {
            this.lastQuery = query;
            this.lastPassages = passages;
            return results;
        }
    }
}
