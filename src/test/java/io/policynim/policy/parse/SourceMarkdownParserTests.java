package io.policynim.policy.parse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceMarkdownParserTests {
    private final SourceMarkdownParser parser = new SourceMarkdownParser();

    @Test
    void preservesTheUnfrontedBodyAndInfersTheWholeUnicodePath() {
        String body = "\r\n> ## early\r\n\r\n> One *literal*\r\n>   second\r\n> ======\r\n";
        var parsed = parser.parse("policies/team/ops/deep/caf\u00e9.md", "\ufeff\ufeff" + body);
        assertThat(parsed.body()).isEqualTo(body);
        assertThat(parsed.bodyStartLine()).isEqualTo(1);
        assertThat(parsed.metadata().title()).isEqualTo("One *literal*\n  second");
        assertThat(parsed.metadata().domain()).isEqualTo("team");
        assertThat(parsed.metadata().policyId()).isEqualTo("TEAM-OPS-DEEP-CAF\u00c9");
    }

    @Test
    void normalizesFrontedBodyAndPreservesQuotedMetadata() {
        var parsed = parser.parse("pack/deep/store.md",
            "---\r\ntitle: ' Stored title '\r\ntags: [\"true\", 'a,b', \"r\\\"q\"]\r\ngrounded_in:\r\n  - one\r\n...\r\n# first\r\n");
        assertThat(parsed.metadata().title()).isEqualTo("Stored title");
        assertThat(parsed.metadata().tags()).containsExactly("true", "a,b", "r\"q");
        assertThat(parsed.metadata().groundedIn()).containsExactly("one");
        assertThat(parsed.metadata().policyId()).isEqualTo("PACK-DEEP-STORE");
        assertThat(parsed.metadata().domain()).isEqualTo("general");
        assertThat(parsed.bodyStartLine()).isEqualTo(7);
        assertThat(parsed.body()).isEqualTo("# first\n");
    }

    @Test
    void invalidRuntimeRulesReportTheMemberAndTheAuthoredLine() {
        assertThatThrownBy(() -> parser.parse("policies/a.md",
            "---\nruntime_rules:\n  - action: file_write\n    effect: block\n    reason: require review\n    path_globs: [true]\n---\n# title"))
            .isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("policies/a.md").hasMessageContaining("line 6").hasMessageContaining("matcher");
    }

    @Test
    void leadingBlankPreventsFrontmatterRecognitionAndDotfilesKeepTheirName() {
        String body = "\n---\npolicy_id: ignored\n---\n\nBody\n";
        var parsed = parser.parse("policies/.md", body);
        assertThat(parsed.body()).isEqualTo(body);
        assertThat(parsed.bodyStartLine()).isEqualTo(1);
        assertThat(parsed.metadata().policyId()).isEqualTo("MD");
        assertThat(parsed.metadata().domain()).isEqualTo(".md");
    }

    @Test
    void usesLiteralHeadingTextAndRetainsTheRemainingColumnsOfListIndentTabs() {
        var setext = parser.parse("policies/blank.md", "- first *lit*\n\tsecond\n  ===\n");
        assertThat(setext.metadata().title()).isEqualTo("first *lit*\n  second");
        var atx = parser.parse("policies/blank.md", "> - # [link](url) \\* &amp; `code` ###\n");
        assertThat(atx.metadata().title()).isEqualTo("[link](url) \\* &amp; `code`");
        var fallback = parser.parse("docs/deep/\u00dfeta_name's.md", "~~~\n# ignored\n~~~\n");
        assertThat(fallback.metadata().title()).isEqualTo("Sseta Name'S");
        assertThat(fallback.metadata().policyId()).isEqualTo("DOCS-DEEP-SSETA-NAME-S");
    }

    @Test
    void retainsAbsoluteRuleLinesAndQuotedMatcherValues() {
        String text = """
            ---
            runtime_rules:
              - action: shell_command
                effect: confirm
                reason: Review
                command_regexes:
                  - "^deploy:"
                  - "1"
              # Between rules
              - action: file_write
                effect: block
                reason: Bound writes
                path_globs: ["true", "a,b"]
            tags: [1, true]
            ---
            Body
            """;
        var source = parser.parseSource("policies/ops.md", text);
        assertThat(source.document().metadata().tags()).containsExactly("1", "true");
        assertThat(source.rules()).hasSize(2);
        var first = source.rules().getFirst();
        assertThat(first.startLine()).isEqualTo(3);
        assertThat(first.endLine()).isEqualTo(8);
        assertThat(first.commandRegexes()).containsExactly("^deploy:", "1");
        assertThat(first.effect()).isEqualTo("confirm");
        var second = source.rules().getLast();
        assertThat(second.startLine()).isEqualTo(10);
        assertThat(second.endLine()).isEqualTo(13);
        assertThat(second.pathGlobs()).containsExactly("true", "a,b");
        assertThat(second.reason()).isEqualTo("Bound writes");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "runtime_rules:", "runtime_rules: []", "  ignored: invalid",
        "tags:\n  invalid: nested", "title: ['unterminated]",
        "runtime_rules:\n  - action: file_write\n    effect: block\n    reason: x\n    other: x",
        "runtime_rules:\n  - action: file_write\n    effect: block\n    effect: confirm",
        "runtime_rules:\n  - action: file_write\n    effect: block\n    reason: x",
        "runtime_rules:\n  - action: file_write\n    effect: block\n    reason: x\n    path_globs: [x]\n    command_regexes: [x]",
        "runtime_rules:\n  - action: file_write\n    effect: block\n    reason: x\n    path_globs:\n      - 1"
    })
    void invalidFrontmatterIsRejectedAndTheSameParserCanReadTheCorrectedSource(String fields) {
        assertThatThrownBy(() -> parser.parse("policies/a.md", "---\n" + fields + "\n---\nbody"))
            .isInstanceOf(InvalidPolicyDocumentException.class);
        assertThat(parser.parse("policies/a.md", "# Recovered").metadata().title()).isEqualTo("Recovered");
    }
}
