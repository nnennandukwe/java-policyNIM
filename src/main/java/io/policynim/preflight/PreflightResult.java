package io.policynim.preflight;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

public record PreflightResult(
    String task,
    @Nullable
    String domain,
    String summary,
    @JsonProperty("applicable_policies") List<PolicyGuidance> applicablePolicies,
    @JsonProperty("plan_steps") List<String> planSteps,
    @JsonProperty("implementation_guidance") List<String> implementationGuidance,
    @JsonProperty("review_flags") List<String> reviewFlags,
    @JsonProperty("tests_required") List<String> testsRequired,
    List<Citation> citations,
    @JsonProperty("insufficient_context") boolean insufficientContext
) {

    static final String INSUFFICIENT_CONTEXT_SUMMARY =
        "PolicyNIM could not find enough grounded policy evidence for this task.";

    public PreflightResult {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        applicablePolicies = applicablePolicies == null ? List.of() : List.copyOf(applicablePolicies);
        planSteps = planSteps == null ? List.of() : List.copyOf(planSteps);
        implementationGuidance = implementationGuidance == null ? List.of() : List.copyOf(implementationGuidance);
        reviewFlags = reviewFlags == null ? List.of() : List.copyOf(reviewFlags);
        testsRequired = testsRequired == null ? List.of() : List.copyOf(testsRequired);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static PreflightResult insufficientContext(PreflightRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return new PreflightResult(
            request.task(),
            request.domain(),
            INSUFFICIENT_CONTEXT_SUMMARY,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            true
        );
    }
}
