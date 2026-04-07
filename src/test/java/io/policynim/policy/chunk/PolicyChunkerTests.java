package io.policynim.policy.chunk;

import io.policynim.policy.model.ParsedPolicyDocument;
import io.policynim.policy.parse.MarkdownPolicyParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyChunkerTests {

    private final MarkdownPolicyParser parser = new MarkdownPolicyParser();
    private final PolicyChunker chunker = new PolicyChunker();

    @Test
    void buildsDeterministicChunkIdsForRepeatedHeadings() {
        ParsedPolicyDocument document = parser.parse(
            "policies/backend/deterministic.md",
            markdown(
                """
                ---
                policy_id: BE-CHUNK-001
                title: Chunk Policy
                ---
                # Chunk Policy

                Intro text

                ## Repeated

                - first

                ## Repeated

                - second
                """
            )
        );

        List<String> firstRun = chunker.chunk(document, parser).stream()
            .map(chunk -> chunk.chunkId() + "@" + chunk.lines())
            .toList();
        List<String> secondRun = chunker.chunk(document, parser).stream()
            .map(chunk -> chunk.chunkId() + "@" + chunk.lines())
            .toList();

        assertThat(firstRun).containsExactly(
            "BE-CHUNK-001:chunk-policy@5-8",
            "BE-CHUNK-001:chunk-policy__repeated@9-12",
            "BE-CHUNK-001:chunk-policy__repeated-2@13-15"
        );
        assertThat(secondRun).isEqualTo(firstRun);
    }

    @Test
    void preservesPreambleAndIgnoresHeadingsInsideCodeFences() {
        ParsedPolicyDocument document = parser.parse(
            "policies/backend/structure.md",
            markdown(
                """
                Intro text before headings.

                More setup context.

                # Root

                ## API Rules

                ```python
                # not-a-heading
                ```

                - keep contracts stable

                ### Edge Cases

                Handle retries carefully.
                """
            )
        );

        List<PolicyChunk> chunks = chunker.chunk(document, parser);

        assertThat(chunks).extracting(PolicyChunk::section).containsExactly(
            "Root > Preamble",
            "Root",
            "Root > API Rules",
            "Root > API Rules > Edge Cases"
        );
        assertThat(chunks).extracting(PolicyChunk::lines).containsExactly("1-4", "5-6", "7-14", "15-17");
        assertThat(chunks.get(2).text()).contains("# not-a-heading");
        assertThat(chunks.get(2).section()).doesNotContain("not-a-heading");
    }

    private static String markdown(String value) {
        return value.stripLeading();
    }
}
