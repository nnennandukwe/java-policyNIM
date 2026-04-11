package io.policynim.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public record GeneratedPreflightDraft(
    String summary,
    @JsonProperty("applicable_policies") List<GeneratedPolicyGuidance> applicablePolicies,
    @JsonProperty("plan_steps") List<String> planSteps,
    @JsonProperty("implementation_guidance") List<String> implementationGuidance,
    @JsonProperty("review_flags") List<String> reviewFlags,
    @JsonProperty("tests_required") List<String> testsRequired,
    @JsonProperty("citation_ids") List<String> citationIds,
    @JsonProperty("insufficient_context") boolean insufficientContext
) {

    public GeneratedPreflightDraft(String summary) {
        this(summary, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false);
    }

    public GeneratedPreflightDraft {
        Objects.requireNonNull(summary, "summary must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        applicablePolicies = applicablePolicies == null ? List.of() : List.copyOf(applicablePolicies);
        planSteps = planSteps == null ? List.of() : List.copyOf(planSteps);
        implementationGuidance = implementationGuidance == null ? List.of() : List.copyOf(implementationGuidance);
        reviewFlags = reviewFlags == null ? List.of() : List.copyOf(reviewFlags);
        testsRequired = testsRequired == null ? List.of() : List.copyOf(testsRequired);
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
    }
}
