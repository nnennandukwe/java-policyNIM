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
        // # non-English test data
        var parsed = parser.parse("policies/team/ops/deep/caf\u00e9.md", "\ufeff\ufeff" + body);
        assertThat(parsed.body()).isEqualTo(body);
        assertThat(parsed.bodyStartLine()).isEqualTo(1);
        assertThat(parsed.metadata().title()).isEqualTo("One *literal*\n  second");
        assertThat(parsed.metadata().domain()).isEqualTo("team");
        // # non-English test data
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
    void keepsUnpairedOpeningDelimitersInOrdinarySourceMetadata() {
        var metadata = parser.parse("policies/a.md", "---\ntitle: 'unterminated\ntags: [a\n---\n# Source\n").metadata();
        assertThat(metadata.title()).isEqualTo("'unterminated");
        assertThat(metadata.tags()).containsExactly("[a");
    }

    @Test
    void acceptsAnApostropheInAnInlineTagWhenTheTagIsQuoted() {
        String policy = "---\ntags: [%s]\n---\n# Source\n";
        assertThatThrownBy(() -> parser.parse("policies/a.md", policy.formatted("can't")))
            .isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessage("Frontmatter contains an unterminated quoted string.");
        var quoted = parser.parse("policies/a.md", policy.formatted("\"can't\""));
        assertThat(quoted.metadata().tags()).containsExactly("can't");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "true", "null"})
    void keepsScalarReasonsAsTextAndRequiresStringMatchers(String value) {
        String policy = """
            ---
            runtime_rules:
              - action: file_write
                effect: block
                reason: %s
                path_globs: [%s]
            ---
            Body
            """;
        String quoted = "\"" + value + "\"";
        var bareReason = parser.parseSource("policies/a.md", policy.formatted(value, quoted)).rules().getFirst();
        var quotedReason = parser.parseSource("policies/a.md", policy.formatted(quoted, quoted)).rules().getFirst();
        assertThat(bareReason.reason()).isEqualTo(value);
        assertThat(quotedReason.reason()).isEqualTo(value);
        assertThat(bareReason.pathGlobs()).containsExactly(value);
        assertThatThrownBy(() -> parser.parse("policies/a.md", policy.formatted(value, value)))
            .isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessage("Policy document policies/a.md has invalid runtime_rules at line 6: "
                + "matcher list items must be strings, not YAML booleans, nulls, numbers, or maps.");
    }

    @Test
    void reportsTheLastItemLineOfADuplicateMatcherField() {
        String policy = """
            ---
            policy_id: TEST-1
            runtime_rules:
              - action: file_write
                effect: block
                reason: Review
                path_globs: [first]
                path_globs:
                  - second
                  - third
            ---
            Body
            """;
        assertThatThrownBy(() -> parser.parse("policies/fixture.md", policy))
            .isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessage("Policy document policies/fixture.md has invalid runtime_rules at line 10: "
                + "duplicate rule key `path_globs`.");
    }

    @Test
    void preservesStringMatchersWithHexOrUnderscoreSpellings() {
        String policy = "---\nruntime_rules:\n  - action: file_write\n    effect: block\n"
            + "    reason: Review\n    path_globs: [%s]\n---\n# Source\n";
        var rule = parser.parseSource("policies/a.md", policy.formatted("0x10, 1_000")).rules().getFirst();
        assertThat(rule.pathGlobs()).containsExactly("0x10", "1_000");
        assertThatThrownBy(() -> parser.parse("policies/a.md", policy.formatted("1000")))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("line 6");
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
        // # non-English test data
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
