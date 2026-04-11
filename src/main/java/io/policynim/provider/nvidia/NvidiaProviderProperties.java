package io.policynim.provider.nvidia;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "policynim.provider.nvidia")
public class NvidiaProviderProperties {

    static final String DEFAULT_CHAT_MODEL = "nvidia/llama-3.3-nemotron-super-49b-v1.5";

    private boolean enabled;

    private String apiKey = "";

    @NotBlank
    private String baseUrl = "https://ai.api.nvidia.com";

    @NotBlank
    private String embeddingModel = "nvidia/llama-3.2-nv-embedqa-1b-v2";

    @NotBlank
    private String rerankModel = "nvidia/llama-nemotron-rerank-1b-v2";

    @NotBlank
    private String chatModel = DEFAULT_CHAT_MODEL;

    public NvidiaProviderProperties() {
    }

    public NvidiaProviderProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String embeddingModel,
        String rerankModel
    ) {
        this(enabled, apiKey, baseUrl, embeddingModel, rerankModel, DEFAULT_CHAT_MODEL);
    }

    public NvidiaProviderProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String embeddingModel,
        String rerankModel,
        String chatModel
    ) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.embeddingModel = embeddingModel;
        this.rerankModel = rerankModel;
        this.chatModel = chatModel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }
}
