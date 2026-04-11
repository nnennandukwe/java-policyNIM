package io.policynim.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public record GeneratedPolicyGuidance(
    @JsonProperty("policy_id") String policyId,
    String title,
    String rationale,
    @JsonProperty("citation_ids") List<String> citationIds
) {

    public GeneratedPolicyGuidance {
        Objects.requireNonNull(policyId, "policyId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(rationale, "rationale must not be null");
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
    }
}
