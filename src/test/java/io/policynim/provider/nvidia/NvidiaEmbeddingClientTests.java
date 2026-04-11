package io.policynim.provider.nvidia;

import io.policynim.provider.EmbeddingInputType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NvidiaEmbeddingClientTests {

    @Test
    void postsOpenAiCompatibleEmbeddingRequestsAndParsesFloatEmbeddings() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        NvidiaEmbeddingClient client = new NvidiaEmbeddingClient(
            restClientBuilder,
            new NvidiaProviderProperties(
                true,
                "test-api-key",
                "https://retrieval.example.test",
                "nvidia/llama-3.2-nv-embedqa-1b-v2",
                "nvidia/llama-nemotron-rerank-1b-v2"
            )
        );

        server.expect(requestTo("https://retrieval.example.test/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-api-key"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json(
                """
                {
                  "input": ["add request ids to every log line"],
                  "model": "nvidia/llama-3.2-nv-embedqa-1b-v2",
                  "input_type": "query",
                  "truncate": "NONE",
                  "encoding_format": "float",
                  "embedding_type": "float"
                }
                """
            ))
            .andRespond(withSuccess(
                """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "index": 0,
                      "embedding": [0.5, -0.25, 1.75]
                    }
                  ],
                  "model": "nvidia/llama-3.2-nv-embedqa-1b-v2",
                  "usage": {
                    "prompt_tokens": 8,
                    "total_tokens": 8
                  }
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        List<float[]> embeddings = client.embed(List.of("add request ids to every log line"), EmbeddingInputType.QUERY);

        assertThat(embeddings).hasSize(1);
        assertThat(embeddings.getFirst()).containsExactly(0.5f, -0.25f, 1.75f);
        server.verify();
    }
}
