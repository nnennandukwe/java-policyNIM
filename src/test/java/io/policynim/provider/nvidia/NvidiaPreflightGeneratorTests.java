package io.policynim.provider.nvidia;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.provider.GeneratedPreflightDraft;
import io.policynim.provider.PolicyGroundingEvidence;
import io.policynim.provider.PolicyPreflightGenerationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NvidiaPreflightGeneratorTests {

    @Test
    void postsChatCompletionRequestsAndParsesGroundedDrafts() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        NvidiaPreflightGenerator generator = new NvidiaPreflightGenerator(
            restClientBuilder,
            new NvidiaProviderProperties(
                true,
                "test-api-key",
                "https://generation.example.test",
                "nvidia/llama-3.2-nv-embedqa-1b-v2",
                "nvidia/llama-nemotron-rerank-1b-v2",
                "nvidia/llama-3.3-nemotron-super-49b-v1.5"
            ),
            new ObjectMapper()
        );

        server.expect(requestTo("https://generation.example.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-api-key"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("\"model\":\"nvidia/llama-3.3-nemotron-super-49b-v1.5\"")))
            .andExpect(content().string(containsString("BACKEND-1")))
            .andExpect(content().string(containsString("Retrieved context JSON:")))
            .andExpect(content().string(containsString("\\\"chunk_id\\\" : \\\"BACKEND-1\\\"")))
            .andExpect(content().string(containsString("\\\"policy_id\\\" : \\\"BACKEND-LOG-001\\\"")))
            .andRespond(withSuccess(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"summary\\":\\"Use request ids.\\",\\"applicable_policies\\":[{\\"policy_id\\":\\"BACKEND-LOG-001\\",\\"title\\":\\"Logging\\",\\"rationale\\":\\"The policy says to use request ids.\\",\\"citation_ids\\":[\\"BACKEND-1\\"]}],\\"implementation_guidance\\":[\\"Thread the id through the job.\\"],\\"review_flags\\":[\\"Avoid unstructured logs.\\"],\\"tests_required\\":[\\"Add a regression test.\\"],\\"citation_ids\\":[\\"BACKEND-1\\"],\\"insufficient_context\\":false}"
                      }
                    }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        GeneratedPreflightDraft draft = generator.generatePreflight(new PolicyPreflightGenerationRequest(
            "add request ids to backend logs",
            "backend",
            3,
            List.of(new PolicyGroundingEvidence(
                "BACKEND-1",
                "policies/backend/logging.md",
                "Logging > Rules",
                "5-8",
                "Use request ids in backend logs.",
                "BACKEND-LOG-001",
                "Logging",
                "backend",
                0.99d
            ))
        ));

        assertThat(draft.summary()).isEqualTo("Use request ids.");
        assertThat(draft.applicablePolicies().getFirst().citationIds()).containsExactly("BACKEND-1");
        assertThat(draft.citationIds()).containsExactly("BACKEND-1");
        assertThat(draft.insufficientContext()).isFalse();
        server.verify();
    }

    @Test
    void rejectsChatResponsesThatDoNotContainValidJson() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        NvidiaPreflightGenerator generator = new NvidiaPreflightGenerator(
            restClientBuilder,
            new NvidiaProviderProperties(
                true,
                "test-api-key",
                "https://generation.example.test",
                "nvidia/llama-3.2-nv-embedqa-1b-v2",
                "nvidia/llama-nemotron-rerank-1b-v2",
                "nvidia/llama-3.3-nemotron-super-49b-v1.5"
            ),
            new ObjectMapper()
        );

        server.expect(requestTo("https://generation.example.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "not json"
                      }
                    }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        assertThatThrownBy(() -> generator.generatePreflight(new PolicyPreflightGenerationRequest(
            "task",
            null,
            1,
            List.of(new PolicyGroundingEvidence(
                "BACKEND-1",
                "policy.md",
                "Rules",
                "1-2",
                "Use request ids.",
                "BACKEND-LOG-001",
                "Logging",
                "backend",
                0.99d
            ))
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("valid JSON");
        server.verify();
    }

    @Test
    void rejectsChatResponsesThatWrapJsonInCommentary() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        NvidiaPreflightGenerator generator = new NvidiaPreflightGenerator(
            restClientBuilder,
            new NvidiaProviderProperties(
                true,
                "test-api-key",
                "https://generation.example.test",
                "nvidia/llama-3.2-nv-embedqa-1b-v2",
                "nvidia/llama-nemotron-rerank-1b-v2",
                "nvidia/llama-3.3-nemotron-super-49b-v1.5"
            ),
            new ObjectMapper()
        );

        server.expect(requestTo("https://generation.example.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Here is the JSON: {\\"summary\\":\\"Use request ids.\\",\\"applicable_policies\\":[],\\"implementation_guidance\\":[],\\"review_flags\\":[],\\"tests_required\\":[],\\"citation_ids\\":[],\\"insufficient_context\\":true}"
                      }
                    }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        assertThatThrownBy(() -> generator.generatePreflight(new PolicyPreflightGenerationRequest(
            "task",
            null,
            1,
            List.of(new PolicyGroundingEvidence(
                "BACKEND-1",
                "policy.md",
                "Rules",
                "1-2",
                "Use request ids.",
                "BACKEND-LOG-001",
                "Logging",
                "backend",
                0.99d
            ))
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("valid JSON");
        server.verify();
    }
}
