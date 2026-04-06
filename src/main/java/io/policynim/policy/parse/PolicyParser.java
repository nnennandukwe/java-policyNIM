package io.policynim.policy.parse;

import io.policynim.policy.model.ParsedPolicyDocument;

import java.util.List;

public interface PolicyParser {

    ParsedPolicyDocument parse(String sourcePath, String text);

    List<DocumentSection> extractSections(ParsedPolicyDocument document);
}
