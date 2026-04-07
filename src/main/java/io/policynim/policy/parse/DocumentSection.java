package io.policynim.policy.parse;

import java.util.List;

public record DocumentSection(
    List<String> headingPath,
    String content,
    int startLine,
    int endLine
) {

    public DocumentSection {
        headingPath = List.copyOf(headingPath);
    }
}
