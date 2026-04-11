package io.policynim.preflight;

import io.policynim.policy.model.PolicyMetadata;
import io.policynim.provider.GeneratedPolicyGuidance;
import io.policynim.provider.GeneratedPreflightDraft;
import io.policynim.provider.PolicyGroundingEvidence;
import io.policynim.provider.PolicyPreflightGenerationRequest;
import io.policynim.provider.PolicyPreflightGenerator;
import io.policynim.retrieval.ScoredPolicyChunk;
import io.policynim.retrieval.SearchRequest;
import io.policynim.retrieval.SearchResult;
import io.policynim.retrieval.SearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PreflightServiceTests {

    @Test
    void materializesGeneratedGuidanceOnlyWhenCitationsMatchRetrievedEvidence() {
        SearchService searchService = mock(SearchService.class);
        given(searchService.search(new SearchRequest("add request ids to logs", "backend", 2))).willReturn(
            new SearchResult(
                "add request ids to logs",
                "backend",
                2,
                List.of(scoredChunk("BACKEND-1", "BACKEND-LOG-001", "Logging", "backend")),
                false
            )
        );
        RecordingPreflightGenerator generator = new RecordingPreflightGenerator(
            new GeneratedPreflightDraft(
                "Use request ids in backend logs.",
                List.of(new GeneratedPolicyGuidance(
                    "BACKEND-LOG-001",
                    "Logging",
                    "The retrieved policy requires request ids.",
                    List.of("BACKEND-1")
                )),
                List.of("Thread the request id through the job boundary."),
                List.of("Log request ids with every backend event."),
                List.of("Avoid unstructured log statements."),
                List.of("Add a logging regression test."),
                List.of("BACKEND-1"),
                false
            )
        );
        PreflightService service = new PreflightService(searchService, generator);

        PreflightResult result = service.preflight(new PreflightRequest("add request ids to logs", "backend", 2));

        assertThat(generator.lastRequest.task()).isEqualTo("add request ids to logs");
        assertThat(generator.lastRequest.domain()).isEqualTo("backend");
        assertThat(generator.lastRequest.evidence()).extracting(PolicyGroundingEvidence::chunkId)
            .containsExactly("BACKEND-1");
        assertThat(result.insufficientContext()).isFalse();
        assertThat(result.summary()).isEqualTo("Use request ids in backend logs.");
        assertThat(result.applicablePolicies()).containsExactly(new PolicyGuidance(
            "BACKEND-LOG-001",
            "Logging",
            "The retrieved policy requires request ids.",
            List.of("BACKEND-1")
        ));
        assertThat(result.citations()).containsExactly(new Citation(
            "BACKEND-LOG-001",
            "Logging",
            "policies/backend/logging.md",
            "Logging > Rules",
            "5-8",
            "BACKEND-1"
        ));
        assertThat(result.testsRequired()).containsExactly("Add a logging regression test.");
    }

    @Test
    void materializesPolicyCitationsMissingFromTopLevelCitationList() {
        SearchService searchService = mock(SearchService.class);
        given(searchService.search(new SearchRequest("add request ids to logs", "backend", 2))).willReturn(
            new SearchResult(
                "add request ids to logs",
                "backend",
                2,
                List.of(
                    scoredChunk("BACKEND-1", "BACKEND-LOG-001", "Logging", "backend"),
                    scoredChunk("BACKEND-2", "BACKEND-LOG-001", "Logging", "backend")
                ),
                false
            )
        );
        RecordingPreflightGenerator generator = new RecordingPreflightGenerator(
            new GeneratedPreflightDraft(
                "Use request ids in backend logs.",
                List.of(new GeneratedPolicyGuidance(
                    "BACKEND-LOG-001",
                    "Logging",
                    "The retrieved policy requires request ids.",
                    List.of("BACKEND-2")
                )),
                List.of("Thread the request id through the job boundary."),
                List.of("Log request ids with every backend event."),
                List.of("Avoid unstructured log statements."),
                List.of("Add a logging regression test."),
                List.of("BACKEND-1"),
                false
            )
        );
        PreflightService service = new PreflightService(searchService, generator);

        PreflightResult result = service.preflight(new PreflightRequest("add request ids to logs", "backend", 2));

        assertThat(result.insufficientContext()).isFalse();
        assertThat(result.applicablePolicies().getFirst().citationIds()).containsExactly("BACKEND-2");
        assertThat(result.citations()).extracting(Citation::chunkId)
            .containsExactly("BACKEND-1", "BACKEND-2");
    }

    @Test
    void failsClosedWhenGeneratedCitationIsNotInRetrievedEvidence() {
        SearchService searchService = mock(SearchService.class);
        given(searchService.search(new SearchRequest("backend guidance", null, 1))).willReturn(
            new SearchResult(
                "backend guidance",
                null,
                1,
                List.of(scoredChunk("BACKEND-1", "BACKEND-LOG-001", "Logging", "backend")),
                false
            )
        );
        PreflightService service = new PreflightService(
            searchService,
            new RecordingPreflightGenerator(new GeneratedPreflightDraft(
                "Unknown citations must not pass.",
                List.of(new GeneratedPolicyGuidance(
                    "BACKEND-LOG-001",
                    "Logging",
                    "Looks grounded but cites the wrong chunk.",
                    List.of("UNKNOWN")
                )),
                List.of(),
                List.of("Do the thing."),
                List.of(),
                List.of(),
                List.of("BACKEND-1"),
                false
            ))
        );

        PreflightResult result = service.preflight(new PreflightRequest("backend guidance", null, 1));

        assertThat(result.insufficientContext()).isTrue();
        assertThat(result.summary()).contains("could not find enough grounded policy evidence");
        assertThat(result.applicablePolicies()).isEmpty();
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void doesNotInvokeGeneratorWhenSearchFindsNoEvidence() {
        SearchService searchService = mock(SearchService.class);
        given(searchService.search(new SearchRequest("unknown workflow", null, 3))).willReturn(
            new SearchResult("unknown workflow", null, 3, List.of(), true)
        );
        RecordingPreflightGenerator generator = new RecordingPreflightGenerator(
            new GeneratedPreflightDraft("Should not be used.")
        );
        PreflightService service = new PreflightService(searchService, generator);

        PreflightResult result = service.preflight(new PreflightRequest("unknown workflow", null, 3));

        assertThat(generator.callCount).isZero();
        assertThat(result.insufficientContext()).isTrue();
        assertThat(result.citations()).isEmpty();
        assertThat(result.summary()).contains("could not find enough grounded policy evidence");
    }

    private static ScoredPolicyChunk scoredChunk(
        String chunkId,
        String policyId,
        String title,
        String domain
    ) {
        return new ScoredPolicyChunk(
            chunkId,
            "policies/" + domain + "/" + title.toLowerCase() + ".md",
            title + " > Rules",
            "5-8",
            "Use request ids in backend logs.",
            new PolicyMetadata(policyId, title, "guidance", domain, List.of("observability"), List.of()),
            0.99d
        );
    }

    private static final class RecordingPreflightGenerator implements PolicyPreflightGenerator {

        private final GeneratedPreflightDraft draft;
        private PolicyPreflightGenerationRequest lastRequest;
        private int callCount;

        private RecordingPreflightGenerator(GeneratedPreflightDraft draft) {
            this.draft = draft;
        }

        @Override
        public GeneratedPreflightDraft generatePreflight(PolicyPreflightGenerationRequest request) {
            this.lastRequest = request;
            this.callCount++;
            return draft;
        }
    }
}
