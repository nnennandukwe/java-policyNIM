package io.policynim.policy.model;

import java.util.List;

public record PolicyMetadata(
    String policyId,
    String title,
    String docType,
    String domain,
    List<String> tags,
    List<String> groundedIn
) {

    public PolicyMetadata {
        tags = List.copyOf(tags);
        groundedIn = List.copyOf(groundedIn);
    }
}
