package io.policynim.provider.nvidia;

import io.policynim.provider.RerankResult;
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

class NvidiaRerankerTests {

    @Test
    void postsRankingRequestsAndReturnsRankedIndexes() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        NvidiaReranker reranker = new NvidiaReranker(
            restClientBuilder,
            new NvidiaProviderProperties(
                true,
                "test-api-key",
                "https://retrieval.example.test",
                "nvidia/llama-3.2-nv-embedqa-1b-v2",
                "nvidia/llama-nemotron-rerank-1b-v2"
            )
        );

        server.expect(requestTo("https://retrieval.example.test/v1/ranking"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-api-key"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json(
                """
                {
                  "model": "nvidia/llama-nemotron-rerank-1b-v2",
                  "query": {
                    "text": "add request ids"
                  },
                  "passages": [
                    {
                      "text": "Trace downstream calls."
                    },
                    {
                      "text": "Add request ids to every log statement."
                    }
                  ],
                  "truncate": "NONE"
                }
                """
            ))
            .andRespond(withSuccess(
                """
                {
                  "rankings": [
                    {
                      "index": 1,
                      "logit": 9.5
                    },
                    {
                      "index": 0,
                      "logit": 2.0
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 32,
                    "total_tokens": 32
                  }
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        List<RerankResult> results = reranker.rerank(
            "add request ids",
            List.of("Trace downstream calls.", "Add request ids to every log statement.")
        );

        assertThat(results).containsExactly(
            new RerankResult(1, 9.5d),
            new RerankResult(0, 2.0d)
        );
        server.verify();
    }
}
