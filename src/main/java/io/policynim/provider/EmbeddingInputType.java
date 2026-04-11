package io.policynim.provider;

public enum EmbeddingInputType {
    QUERY("query"),
    PASSAGE("passage");

    private final String apiValue;

    EmbeddingInputType(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
