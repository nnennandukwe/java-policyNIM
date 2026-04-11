package io.policynim.provider.nvidia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.policynim.provider.PolicyReranker;
import io.policynim.provider.RerankResult;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

final class NvidiaReranker implements PolicyReranker {

    private final RestClient restClient;
    private final NvidiaProviderProperties properties;

    NvidiaReranker(RestClient.Builder restClientBuilder, NvidiaProviderProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
            .baseUrl(properties.getBaseUrl())
            .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
            .build();
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> passages) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(passages, "passages must not be null");
        if (query.isBlank() || passages.isEmpty()) {
            return List.of();
        }

        RankResponse response = restClient.post()
            .uri("/v1/ranking")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new RankRequest(
                properties.getRerankModel(),
                new QueryData(query),
                passages.stream().map(Passage::new).toList(),
                "NONE"
            ))
            .retrieve()
            .body(RankResponse.class);

        if (response == null || response.rankings() == null) {
            throw new IllegalStateException("NVIDIA rerank response did not include rankings.");
        }

        return response.rankings().stream()
            .map(ranking -> new RerankResult(ranking.index(), ranking.logit()))
            .toList();
    }

    private record RankRequest(String model, QueryData query, List<Passage> passages, String truncate) {
    }

    private record QueryData(String text) {
    }

    private record Passage(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RankResponse(List<Rank> rankings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Rank(int index, double logit) {
    }
}
