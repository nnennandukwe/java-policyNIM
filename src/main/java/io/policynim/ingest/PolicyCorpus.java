package io.policynim.ingest;

import io.policynim.policy.model.ParsedPolicyDocument;

import java.nio.file.Path;
import java.util.List;

public interface PolicyCorpus {

    List<ParsedPolicyDocument> load(Path corpusRoot);
}
