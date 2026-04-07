package io.policynim.policy.parse;

import io.policynim.policy.model.ParsedPolicyDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownPolicyParserTests {

    private final MarkdownPolicyParser parser = new MarkdownPolicyParser();

    @Test
    void parsesFrontmatterAndNormalizesMetadata() {
        ParsedPolicyDocument document = parser.parse(
            "policies/backend/perfect.md",
            """
                ---
                policy_id: BE-DEMO-001
                title: Perfect Policy
                doc_type: standard
                domain: backend
                tags:
                  - demo
                grounded_in:
                  - https://example.com/source
                ---
                # Perfect Policy

                ## Intent

                Keep the service safe.
                """
        );

        assertThat(document.sourcePath()).isEqualTo("policies/backend/perfect.md");
        assertThat(document.metadata().policyId()).isEqualTo("BE-DEMO-001");
        assertThat(document.metadata().title()).isEqualTo("Perfect Policy");
        assertThat(document.metadata().docType()).isEqualTo("standard");
        assertThat(document.metadata().domain()).isEqualTo("backend");
        assertThat(document.metadata().tags()).containsExactly("demo");
        assertThat(document.metadata().groundedIn()).containsExactly("https://example.com/source");
    }

    @Test
    void infersMetadataFromPathAndHeadingWhenFrontmatterIsMissing() {
        ParsedPolicyDocument document = parser.parse(
            "policies/security/session-boundaries.md",
            """
                # Session Boundaries

                ## Intent

                Tokens must expire cleanly.
                """
        );

        assertThat(document.metadata().title()).isEqualTo("Session Boundaries");
        assertThat(document.metadata().policyId()).isEqualTo("SECURITY-SESSION-BOUNDARIES");
        assertThat(document.metadata().domain()).isEqualTo("security");
        assertThat(document.metadata().docType()).isEqualTo("guidance");
        assertThat(document.metadata().tags()).isEmpty();
        assertThat(document.metadata().groundedIn()).isEmpty();
    }

    @Test
    void rejectsMalformedFrontmatter() {
        assertThatThrownBy(() -> parser.parse(
            "policies/backend/broken.md",
            """
                ---
                title Broken
                ---
                # Broken
                """
        ))
            .isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("malformed YAML frontmatter");
    }

    @Test
    void rejectsCustomTaggedYamlValues() {
        assertThatThrownBy(() -> parser.parse(
            "policies/backend/tagged.md",
            """
                ---
                title: !!javax.script.ScriptEngineManager []
                ---
                # Tagged
                """
        ))
            .isInstanceOf(InvalidPolicyDocumentException.class);
    }
}
