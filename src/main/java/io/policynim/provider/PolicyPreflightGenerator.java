package io.policynim.provider;

import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface PolicyPreflightGenerator {

    GeneratedPreflightDraft generatePreflight(PolicyPreflightGenerationRequest request);

    static PolicyPreflightGenerator noOp() {
        return request -> {
            Objects.requireNonNull(request, "request must not be null");
            return new GeneratedPreflightDraft(
                "PolicyNIM could not find enough grounded policy evidence for this task.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true
            );
        };
    }
}
