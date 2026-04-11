package io.policynim.retrieval;

import io.policynim.provider.EmbeddingInputType;
import io.policynim.provider.PolicyEmbeddingModel;
import io.policynim.provider.PolicyReranker;
import io.policynim.provider.RerankResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public class SearchService {

    private final PolicyChunkReadStore readStore;
    private final PolicyEmbeddingModel embeddingModel;
    private final PolicyReranker reranker;

    public SearchService(PolicyChunkReadStore readStore) {
        this(readStore, PolicyEmbeddingModel.noOp(), PolicyReranker.noOp());
    }

    public SearchService(
        PolicyChunkReadStore readStore,
        PolicyEmbeddingModel embeddingModel,
        PolicyReranker reranker
    ) {
        this.readStore = Objects.requireNonNull(readStore, "readStore must not be null");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        this.reranker = Objects.requireNonNull(reranker, "reranker must not be null");
    }

    public SearchResult search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<ScoredPolicyChunk> candidates = retrieveCandidates(request);
        List<ScoredPolicyChunk> hits = rerankCandidates(request.query(), request.topK(), candidates);
        return new SearchResult(
            request.query(),
            request.domain(),
            request.topK(),
            hits,
            hits.isEmpty()
        );
    }

    private List<ScoredPolicyChunk> retrieveCandidates(SearchRequest request) {
        if (!hasSearchableRows()) {
            return readStore.search(request.query(), request.domain(), request.topK());
        }

        List<float[]> queryEmbeddings = embeddingModel.embed(List.of(request.query()), EmbeddingInputType.QUERY);
        if (!queryEmbeddings.isEmpty()) {
            List<ScoredPolicyChunk> semanticCandidates = readStore.searchByEmbedding(
                queryEmbeddings.getFirst(),
                request.domain(),
                request.topK()
            );
            if (!semanticCandidates.isEmpty()) {
                return semanticCandidates;
            }
        }
        return readStore.search(request.query(), request.domain(), request.topK());
    }

    private boolean hasSearchableRows() {
        return readStore.exists() && readStore.rowCount() > 0;
    }

    private List<ScoredPolicyChunk> rerankCandidates(String query, int topK, List<ScoredPolicyChunk> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<RerankResult> reranked = reranker.rerank(
            query,
            candidates.stream().map(ScoredPolicyChunk::text).toList()
        );
        if (reranked.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }

        List<ScoredPolicyChunk> reordered = new ArrayList<>();
        LinkedHashSet<Integer> seenIndexes = new LinkedHashSet<>();
        for (RerankResult result : reranked) {
            int index = result.index();
            if (index < 0 || index >= candidates.size() || !seenIndexes.add(index)) {
                continue;
            }
            ScoredPolicyChunk candidate = candidates.get(index);
            reordered.add(new ScoredPolicyChunk(
                candidate.chunkId(),
                candidate.path(),
                candidate.section(),
                candidate.lines(),
                candidate.text(),
                candidate.policy(),
                result.score()
            ));
            if (reordered.size() == topK) {
                return List.copyOf(reordered);
            }
        }
        for (int index = 0; index < candidates.size() && reordered.size() < topK; index++) {
            if (seenIndexes.add(index)) {
                reordered.add(candidates.get(index));
            }
        }

        if (reordered.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }
        return List.copyOf(reordered);
    }
}
