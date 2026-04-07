package io.policynim.provider.nvidia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.policynim.provider.EmbeddingInputType;
import io.policynim.provider.PolicyEmbeddingModel;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

final class NvidiaEmbeddingClient implements PolicyEmbeddingModel {

    private final RestClient restClient;
    private final NvidiaProviderProperties properties;

    NvidiaEmbeddingClient(RestClient.Builder restClientBuilder, NvidiaProviderProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
            .baseUrl(properties.getBaseUrl())
            .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
            .build();
    }

    @Override
    public List<float[]> embed(List<String> inputs, EmbeddingInputType inputType) {
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(inputType, "inputType must not be null");
        if (inputs.isEmpty()) {
            return List.of();
        }

        EmbeddingResponse response = restClient.post()
            .uri("/v1/embeddings")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new EmbeddingRequest(
                inputs,
                properties.getEmbeddingModel(),
                inputType.apiValue(),
                "NONE",
                "float",
                "float"
            ))
            .retrieve()
            .body(EmbeddingResponse.class);

        if (response == null || response.data() == null) {
            throw new IllegalStateException("NVIDIA embedding response did not include embedding data.");
        }

        return response.data().stream()
            .map(EmbeddingData::embedding)
            .map(NvidiaEmbeddingClient::toFloatArray)
            .toList();
    }

    private static float[] toFloatArray(List<Double> values) {
        float[] embedding = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            embedding[index] = values.get(index).floatValue();
        }
        return embedding;
    }

    private record EmbeddingRequest(
        List<String> input,
        String model,
        @JsonProperty("input_type") String inputType,
        String truncate,
        @JsonProperty("encoding_format") String encodingFormat,
        @JsonProperty("embedding_type") String embeddingType
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(List<Double> embedding) {
    }
}
