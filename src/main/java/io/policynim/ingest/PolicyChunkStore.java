package io.policynim.ingest;

@FunctionalInterface
public interface PolicyChunkStore {

    void replaceAll(IngestedPolicyCorpus corpus);

    static PolicyChunkStore noOp() {
        return corpus -> {
        };
    }
}
