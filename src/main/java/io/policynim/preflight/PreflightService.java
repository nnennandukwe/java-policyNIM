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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PreflightService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreflightService.class);

    private final SearchService searchService;
    private final PolicyPreflightGenerator generator;

    public PreflightService(SearchService searchService, PolicyPreflightGenerator generator) {
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
    }

    public PreflightResult preflight(PreflightRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SearchResult searchResult = searchService.search(new SearchRequest(
            request.task(),
            request.domain(),
            request.topK()
        ));
        if (searchResult.insufficientContext() || searchResult.hits().isEmpty()) {
            return PreflightResult.insufficientContext(request);
        }

        List<PolicyGroundingEvidence> evidence = searchResult.hits().stream()
            .map(PreflightService::toEvidence)
            .toList();
        GeneratedPreflightDraft draft = generator.generatePreflight(new PolicyPreflightGenerationRequest(
            request.task(),
            request.domain(),
            request.topK(),
            evidence
        ));
        return validateAndMaterialize(request, searchResult.hits(), draft);
    }

    private PreflightResult validateAndMaterialize(
        PreflightRequest request,
        List<ScoredPolicyChunk> context,
        GeneratedPreflightDraft draft
    ) {
        if (draft.insufficientContext()) {
            return PreflightResult.insufficientContext(request);
        }

        Map<String, ScoredPolicyChunk> contextById = new LinkedHashMap<>();
        for (ScoredPolicyChunk chunk : context) {
            contextById.put(chunk.chunkId(), chunk);
        }
        if (contextById.isEmpty()) {
            return PreflightResult.insufficientContext(request);
        }

        List<String> citationIds = materializedCitationIds(draft);
        if (citationIds.isEmpty() || containsUnknownCitation(citationIds, contextById)) {
            return PreflightResult.insufficientContext(request);
        }

        List<PolicyGuidance> applicablePolicies = new ArrayList<>();
        for (GeneratedPolicyGuidance policy : draft.applicablePolicies()) {
            List<String> policyCitationIds = orderedUnique(policy.citationIds());
            if (policyCitationIds.isEmpty()) {
                continue;
            }
            PolicyMetadataResolution policyMetadataResolution = resolvePolicyMetadata(policyCitationIds, contextById);
            if (policyMetadataResolution.failed()) {
                logPolicyMetadataResolutionFailure(request, policyCitationIds, policyMetadataResolution);
                return PreflightResult.insufficientContext(request);
            }
            applicablePolicies.add(new PolicyGuidance(
                policyMetadataResolution.policyMetadata().policyId(),
                policyMetadataResolution.policyMetadata().title(),
                policy.rationale(),
                policyCitationIds
            ));
        }
        if (applicablePolicies.isEmpty()) {
            return PreflightResult.insufficientContext(request);
        }

        List<Citation> citations = citationIds.stream()
            .map(contextById::get)
            .map(PreflightService::toCitation)
            .toList();
        if (citations.isEmpty()) {
            return PreflightResult.insufficientContext(request);
        }

        return new PreflightResult(
            request.task(),
            request.domain(),
            draft.summary(),
            applicablePolicies,
            draft.planSteps(),
            draft.implementationGuidance(),
            draft.reviewFlags(),
            draft.testsRequired(),
            citations,
            false
        );
    }

    private static PolicyGroundingEvidence toEvidence(ScoredPolicyChunk chunk) {
        PolicyMetadata policy = chunk.policy();
        return new PolicyGroundingEvidence(
            chunk.chunkId(),
            chunk.path(),
            chunk.section(),
            chunk.lines(),
            chunk.text(),
            policy.policyId(),
            policy.title(),
            policy.domain(),
            chunk.score()
        );
    }

    private static Citation toCitation(ScoredPolicyChunk chunk) {
        PolicyMetadata policy = chunk.policy();
        return new Citation(
            policy.policyId(),
            policy.title(),
            chunk.path(),
            chunk.section(),
            chunk.lines(),
            chunk.chunkId()
        );
    }

    private static boolean containsUnknownCitation(
        List<String> citationIds,
        Map<String, ScoredPolicyChunk> contextById
    ) {
        return citationIds.stream().anyMatch(citationId -> !contextById.containsKey(citationId));
    }

    private static PolicyMetadataResolution resolvePolicyMetadata(
        List<String> citationIds,
        Map<String, ScoredPolicyChunk> contextById
    ) {
        PolicyMetadata resolved = null;
        for (String citationId : citationIds) {
            ScoredPolicyChunk chunk = contextById.get(citationId);
            if (chunk == null) {
                return PolicyMetadataResolution.missingChunk(citationId);
            }
            if (chunk.policy() == null) {
                return PolicyMetadataResolution.missingPolicyMetadata(citationId);
            }
            PolicyMetadata candidate = chunk.policy();
            if (resolved == null) {
                resolved = candidate;
                continue;
            }
            if (!samePolicyMetadata(resolved, candidate)) {
                return PolicyMetadataResolution.mismatchedPolicyMetadata(citationId, resolved, candidate);
            }
        }
        return PolicyMetadataResolution.resolved(resolved);
    }

    private static boolean samePolicyMetadata(PolicyMetadata left, PolicyMetadata right) {
        return Objects.equals(left.policyId(), right.policyId())
            && Objects.equals(left.title(), right.title());
    }

    private static void logPolicyMetadataResolutionFailure(
        PreflightRequest request,
        List<String> citationIds,
        PolicyMetadataResolution resolution
    ) {
        LOGGER.warn(
            "event=preflight_policy_metadata_resolution_failed reason={} domain={} top_k={} citation_ids={} " +
                "failure_citation_id={} expected_policy_id={} expected_title={} actual_policy_id={} actual_title={}",
            resolution.failure(),
            request.domain(),
            request.topK(),
            citationIds,
            resolution.failureCitationId(),
            policyId(resolution.expectedPolicyMetadata()),
            title(resolution.expectedPolicyMetadata()),
            policyId(resolution.actualPolicyMetadata()),
            title(resolution.actualPolicyMetadata())
        );
    }

    private static String policyId(PolicyMetadata policyMetadata) {
        return policyMetadata == null ? null : policyMetadata.policyId();
    }

    private static String title(PolicyMetadata policyMetadata) {
        return policyMetadata == null ? null : policyMetadata.title();
    }

    private static List<String> materializedCitationIds(GeneratedPreflightDraft draft) {
        List<String> policyCitationIds = draft.applicablePolicies().stream()
            .flatMap(policy -> policy.citationIds().stream())
            .toList();
        if (draft.citationIds().isEmpty()) {
            return orderedUnique(policyCitationIds);
        }

        List<String> citationIds = new ArrayList<>(draft.citationIds());
        citationIds.addAll(policyCitationIds);
        return orderedUnique(citationIds);
    }

    private static List<String> orderedUnique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private enum PolicyMetadataResolutionFailure {
        MISSING_CHUNK,
        MISSING_POLICY_METADATA,
        POLICY_METADATA_MISMATCH
    }

    private record PolicyMetadataResolution(
        PolicyMetadata policyMetadata,
        PolicyMetadataResolutionFailure failure,
        String failureCitationId,
        PolicyMetadata expectedPolicyMetadata,
        PolicyMetadata actualPolicyMetadata
    ) {

        private static PolicyMetadataResolution resolved(PolicyMetadata policyMetadata) {
            return new PolicyMetadataResolution(policyMetadata, null, null, null, null);
        }

        private static PolicyMetadataResolution missingChunk(String citationId) {
            return new PolicyMetadataResolution(
                null,
                PolicyMetadataResolutionFailure.MISSING_CHUNK,
                citationId,
                null,
                null
            );
        }

        private static PolicyMetadataResolution missingPolicyMetadata(String citationId) {
            return new PolicyMetadataResolution(
                null,
                PolicyMetadataResolutionFailure.MISSING_POLICY_METADATA,
                citationId,
                null,
                null
            );
        }

        private static PolicyMetadataResolution mismatchedPolicyMetadata(
            String citationId,
            PolicyMetadata expectedPolicyMetadata,
            PolicyMetadata actualPolicyMetadata
        ) {
            return new PolicyMetadataResolution(
                null,
                PolicyMetadataResolutionFailure.POLICY_METADATA_MISMATCH,
                citationId,
                expectedPolicyMetadata,
                actualPolicyMetadata
            );
        }

        private boolean failed() {
            return failure != null;
        }
    }
}
