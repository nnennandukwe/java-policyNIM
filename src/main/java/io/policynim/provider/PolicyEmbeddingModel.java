package io.policynim.provider;

import java.util.List;

@FunctionalInterface
public interface PolicyEmbeddingModel {

    List<float[]> embed(List<String> inputs, EmbeddingInputType inputType);

    static PolicyEmbeddingModel noOp() {
        return (inputs, inputType) -> List.of();
    }
}
