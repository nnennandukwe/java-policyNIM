package io.policynim.provider.nvidia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.provider.GeneratedPreflightDraft;
import io.policynim.provider.PolicyGroundingEvidence;
import io.policynim.provider.PolicyPreflightGenerationRequest;
import io.policynim.provider.PolicyPreflightGenerator;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

final class NvidiaPreflightGenerator implements PolicyPreflightGenerator {

    private final RestClient restClient;
    private final NvidiaProviderProperties properties;
    private final ObjectMapper objectMapper;

    NvidiaPreflightGenerator(
        RestClient.Builder restClientBuilder,
        NvidiaProviderProperties properties,
        ObjectMapper objectMapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
            .baseUrl(properties.getBaseUrl())
            .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
            .build();
    }

    @Override
    public GeneratedPreflightDraft generatePreflight(PolicyPreflightGenerationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ChatCompletionResponse response = restClient.post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ChatCompletionRequest(
                properties.getChatModel(),
                List.of(
                    new ChatMessage("system", systemPrompt()),
                    new ChatMessage("user", userPrompt(request))
                ),
                0.0d,
                1.0d
            ))
            .retrieve()
            .body(ChatCompletionResponse.class);

        String content = extractContent(response);
        return parseDraft(content);
    }

    private GeneratedPreflightDraft parseDraft(String content) {
        try {
            return objectMapper.readValue(content.trim(), GeneratedPreflightDraft.class);
        }
        catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "NVIDIA preflight response did not contain valid JSON.",
                exception
            );
        }
    }

    private static String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("NVIDIA preflight response did not include completion choices.");
        }
        ChatChoice choice = response.choices().getFirst();
        if (choice.message() == null || choice.message().content() == null || choice.message().content().isBlank()) {
            throw new IllegalStateException("NVIDIA preflight response did not include completion content.");
        }
        return choice.message().content();
    }

    private static String systemPrompt() {
        return """
            You are PolicyNIM's grounded policy synthesis engine.
            Return only valid JSON. Do not use markdown fences or commentary.
            The JSON must match this shape:
            {
              "summary": "string",
              "applicable_policies": [
                {
                  "policy_id": "string",
                  "title": "string",
                  "rationale": "string",
                  "citation_ids": ["chunk-id"]
                }
              ],
              "plan_steps": ["string"],
              "implementation_guidance": ["string"],
              "review_flags": ["string"],
              "tests_required": ["string"],
              "citation_ids": ["chunk-id"],
              "insufficient_context": false
            }
            Rules:
            - Cite only chunk_id values that appear in the retrieved context.
            - Do not invent chunk IDs.
            - If the evidence is insufficient, set insufficient_context to true and keep the lists empty.
            - Keep the summary concise and task-specific.
            """;
    }

    private String userPrompt(PolicyPreflightGenerationRequest request) {
        return """
            Task: %s
            Domain: %s
            Target top_k: %d
            Retrieved context JSON:
            %s
            """.formatted(
            request.task(),
            request.domain() == null ? "none" : request.domain(),
            request.topK(),
            formatEvidence(request.evidence())
        );
    }

    private String formatEvidence(List<PolicyGroundingEvidence> evidence) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(evidence.stream()
                    .map(PromptEvidence::from)
                    .toList());
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("NVIDIA preflight evidence could not be serialized as JSON.", exception);
        }
    }

    private record PromptEvidence(
        @JsonProperty("chunk_id") String chunkId,
        String path,
        String section,
        String lines,
        String text,
        @JsonProperty("policy_id") String policyId,
        String title,
        String domain,
        double score
    ) {

        private static PromptEvidence from(PolicyGroundingEvidence evidence) {
            return new PromptEvidence(
                evidence.chunkId(),
                evidence.path(),
                evidence.section(),
                evidence.lines(),
                evidence.text(),
                evidence.policyId(),
                evidence.title(),
                evidence.domain(),
                evidence.score()
            );
        }
    }

    private record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        @JsonProperty("top_p") double topP
    ) {
    }

    private record ChatMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<ChatChoice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatChoice(ChatMessageResponse message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatMessageResponse(String content) {
    }
}
