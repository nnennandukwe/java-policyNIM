package io.policynim.retrieval;

import java.util.List;

public interface PolicyChunkReadStore {

    boolean exists();

    long rowCount();

    default boolean hasRows() {
        return exists() && rowCount() > 0;
    }

    List<ScoredPolicyChunk> search(String query, String domain, int limit);

    default List<ScoredPolicyChunk> searchByEmbedding(float[] embedding, String domain, int limit) {
        return List.of();
    }

    static PolicyChunkReadStore noOp() {
        return NoOpPolicyChunkReadStore.INSTANCE;
    }

    enum NoOpPolicyChunkReadStore implements PolicyChunkReadStore {
        INSTANCE;

        @Override
        public boolean exists() {
            return false;
        }

        @Override
        public long rowCount() {
            return 0;
        }

        @Override
        public boolean hasRows() {
            return false;
        }

        @Override
        public List<ScoredPolicyChunk> search(String query, String domain, int limit) {
            return List.of();
        }
    }
}
