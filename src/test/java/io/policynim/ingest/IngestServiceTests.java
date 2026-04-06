package io.policynim.ingest;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.MarkdownPolicyParser;
import io.policynim.policy.chunk.PolicyChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void ingestsMarkdownPoliciesFromTheCorpusRootInStableOrder() throws IOException {
        Path policiesDir = tempDir.resolve("policies");
        writePolicy(
            policiesDir.resolve("backend/logging.md"),
            """
                ---
                policy_id: BACKEND-LOG-001
                title: Logging
                domain: backend
                ---
                # Logging

                ## Rules

                Log with context.
                """
        );
        writePolicy(
            policiesDir.resolve("security/session-boundaries.md"),
            """
                # Session Boundaries

                ## Rules

                Expire session tokens.
                """
        );

        IngestService service = new IngestService(
            new FileSystemPolicyCorpus(new MarkdownPolicyParser()),
            new PolicyChunker()
        );

        IngestedPolicyCorpus result = service.ingest(new IngestCommand(policiesDir));

        assertThat(result.corpusRoot()).isEqualTo(policiesDir);
        assertThat(result.documents()).hasSize(2);
        assertThat(result.chunks()).extracting(chunk -> chunk.chunkId() + "@" + chunk.path()).containsExactly(
            "BACKEND-LOG-001:logging@policies/backend/logging.md",
            "BACKEND-LOG-001:logging__rules@policies/backend/logging.md",
            "SECURITY-SESSION-BOUNDARIES:session-boundaries@policies/security/session-boundaries.md",
            "SECURITY-SESSION-BOUNDARIES:session-boundaries__rules@policies/security/session-boundaries.md"
        );
    }

    @Test
    void rejectsDuplicateEffectivePolicyIdsAcrossTheCorpus() throws IOException {
        Path policiesDir = tempDir.resolve("policies");
        writePolicy(
            policiesDir.resolve("backend/first.md"),
            """
                ---
                policy_id: DUP-001
                ---
                # Duplicate Policy

                Body text.
                """
        );
        writePolicy(
            policiesDir.resolve("backend/second.md"),
            """
                ---
                policy_id: DUP-001
                ---
                # Duplicate Policy

                Body text.
                """
        );

        IngestService service = new IngestService(
            new FileSystemPolicyCorpus(new MarkdownPolicyParser()),
            new PolicyChunker()
        );

        assertThatThrownBy(() -> service.ingest(new IngestCommand(policiesDir)))
            .isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("Duplicate effective policy_id");
    }

    private static void writePolicy(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.stripLeading());
    }
}
