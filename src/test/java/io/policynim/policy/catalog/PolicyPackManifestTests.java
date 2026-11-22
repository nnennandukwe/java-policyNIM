package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyPackManifestTests {

    private static final String MANIFEST = """
        schema_version = 1
        pack_id = "team.logging"
        version = "1.0.0"
        [[policies]]
        policy_id = "LOG-001"
        path = "policy.md"
        """;

    private static PolicyPackManifest parse(String input) {
        return PolicyPackManifest.parse(input.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validatesTheActualSchemaIntegerBeforeExposingMembers() {
        assertThatThrownBy(() -> parse(MANIFEST.replace("schema_version = 1", "schema_version = 1000000000000000001")))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("schema_version");
        var pack = parse(MANIFEST);
        assertThat(pack.packId()).isEqualTo("team.logging");
        assertThat(pack.version()).isEqualTo("1.0.0");
        assertThat(pack.policies()).singleElement().satisfies(member -> {
            assertThat(member.policyId()).isEqualTo("LOG-001");
            assertThat(member.path()).isEqualTo("policy.md");
        });
    }

    private static final String SOURCE = """
        [[policies.sources]]
        title = "  Logging reference  "
        url = "HTTPS://EXAMPLE.ORG:443"
        """;

    @Test
    void refusesYearZeroBeforeReturningCorrectedLeapDateAndNormalizedSourceDefaults() {
        assertThatThrownBy(() -> parse(MANIFEST + SOURCE + "retrieved_on = 0000-01-01"))
            .isInstanceOf(InvalidPolicyDocumentException.class);
        var pack = parse(MANIFEST + SOURCE + "retrieved_on = 2024-02-29");
        var provenance = pack.policies().getFirst().provenance("sha256:" + "a".repeat(64), pack);
        assertThat(provenance.toString()).isEqualTo("""
            {"content_digest":"sha256:%s","pack_id":"team.logging","pack_version":"1.0.0",\
            "requirement_level":"unknown","applicability":[],"grounding_kind":"legacy_unknown",\
            "sources":[{"title":"Logging reference","url":"https://example.org/","revision":null,\
            "sections":[],"retrieved_on":"2024-02-29","license":null,"attribution":null,"change_notice":null}]}
            """.formatted("a".repeat(64)).strip());
    }

    @Test
    void handlesMidnightDatesWithoutChangingTheLocalDayOrLiterals() {
        String first = SOURCE.replace("  Logging reference  ", "literal +23:59");
        String second = SOURCE.replace("  Logging reference  ", "literal -18:01");
        String both = MANIFEST + first + "retrieved_on = 2024-02-29T00:00:00+23:59\n"
            + second + "retrieved_on = 2024-02-28T00:00:00-18:01\n";
        byte[] bytes = both.getBytes(StandardCharsets.UTF_8);
        var pack = PolicyPackManifest.parse(bytes);
        assertThat(bytes).containsExactly(both.getBytes(StandardCharsets.UTF_8));
        var sources = pack.policies().getFirst().provenance("sha256:" + "f".repeat(64), pack).get("sources");
        assertThat(sources.get(0).get("title").textValue()).isEqualTo("literal +23:59");
        assertThat(sources.get(0).get("retrieved_on").textValue()).isEqualTo("2024-02-29");
        assertThat(sources.get(1).get("title").textValue()).isEqualTo("literal -18:01");
        assertThat(sources.get(1).get("retrieved_on").textValue()).isEqualTo("2024-02-28");
        for (String invalid : List.of("+24:00", "+19:99", "-23:60")) {
            assertThatThrownBy(() -> parse(both.replace("00:00:00+23:59", "00:00:00" + invalid)))
                .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("TOML");
        }
        assertThatThrownBy(() -> parse(both.replace("00:00:00+23:59", "00:00:01+23:59")))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("policies.0.sources.0.retrieved_on");
    }

    @Test
    void preservesDeclaredStringsAndOrderInThePolicyProvenance() {
        String members = MANIFEST
            + """
            requirement_level = "recommended"
            applicability = ["frontend", " backend ", ""]
            grounding_kind = "adaptation"
            """
            + SOURCE.replace("  Logging reference  ", "\u00a0Logging reference\u0085")
            + """
            revision = " revision-1 "
            sections = ["2.1", " 2.2 "]
            license = " CC-BY-4.0 "
            attribution = " Example Authors "
            change_notice = " Adapted. "
            """
            + SOURCE.replace("Logging reference", "Second source");
        var pack = parse(members);
        var provenance = pack.policies().getFirst().provenance("sha256:" + "0".repeat(64), pack);
        assertThat(provenance.get("requirement_level").textValue()).isEqualTo("recommended");
        assertThat(provenance.get("grounding_kind").textValue()).isEqualTo("adaptation");
        assertThat(provenance.get("applicability").toString()).isEqualTo("[\"frontend\",\" backend \",\"\"]");
        var sources = provenance.get("sources");
        assertThat(sources.get(0).get("title").textValue()).isEqualTo("Logging reference");
        assertThat(sources.get(0).get("sections").toString()).isEqualTo("[\"2.1\",\" 2.2 \"]");
        assertThat(sources.get(0).get("revision").textValue()).isEqualTo("revision-1");
        assertThat(sources.get(0).get("license").textValue()).isEqualTo("CC-BY-4.0");
        assertThat(sources.get(0).get("attribution").textValue()).isEqualTo("Example Authors");
        assertThat(sources.get(0).get("change_notice").textValue()).isEqualTo("Adapted.");
        assertThat(sources.get(1).get("title").textValue()).isEqualTo("Second source");
    }

    @Test
    void reportsAllInvalidFieldsWithoutEchoingTheirValues() {
        String invalid = MANIFEST.replace("team.logging", "private-pack-value?")
            + "enabled = false\napplicability = [true]\n" + SOURCE.replace("HTTPS://EXAMPLE.ORG:443", "https://secret-sentinel@example.org")
            + "sections = [42]\nretrieved_on = 12:00:00\n";
        assertThatThrownBy(() -> parse(invalid)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContainingAll("pack_id", "policies.0.enabled", "policies.0.applicability.0",
                "policies.0.sources.0.url", "policies.0.sources.0.sections.0", "policies.0.sources.0.retrieved_on")
            .hasMessageNotContaining("secret-sentinel").hasMessageNotContaining("private-pack-value");
    }

    @Test
    void rejectsDuplicateMembershipWithoutCollapsingHarmlessPathSpellings() {
        String next = """
            [[policies]]
            policy_id = "OTHER"
            path = "other.md"
            """;
        assertThatThrownBy(() -> parse(MANIFEST + next.replace("OTHER", "LOG-001")))
            .hasMessageContaining("Duplicate policy_id");
        assertThatThrownBy(() -> parse(MANIFEST + next.replace("other.md", "policy.md")))
            .hasMessageContaining("Duplicate path");
        var pack = parse(MANIFEST + next.replace("other.md", "./policy.md"));
        assertThat(pack.policies()).extracting(PolicyPackManifest.Member::path).containsExactly("policy.md", "./policy.md");
    }

    @Test
    void keepsUrlNormalizationOutOfTheQueryAndFragment() {
        String data = MANIFEST + SOURCE.replace("HTTPS://EXAMPLE.ORG:443", "HTTPS://EXAMPLE.ORG:443/a\\\\b?q=\\\\value#f\\\\g");
        var pack = parse(data);
        assertThat(pack.policies().getFirst().sources().getFirst().url()).isEqualTo("https://example.org/a/b?q=\\value#f\\g");
        for (String invalid : List.of("file:///missing", "https://user:password@example.invalid",
            "x-test://example.invalid", "http://[invalid")) {
            assertThatThrownBy(() -> parse(MANIFEST + SOURCE.replace("HTTPS://EXAMPLE.ORG:443", invalid)))
                .hasMessageContaining("policies.0.sources.0.url").hasMessageNotContaining(invalid);
        }
    }

    @Test
    void acceptsExactMidnightsAtTheReferenceMicrosecondPrecision() {
        var valid = Map.of(
            "2024-02-29T00:00:00.0000009Z", "2024-02-29",
            "\"2024-02-29T00:00:00.0000009+23:59\"", "2024-02-29",
            "1709164800000", "2024-02-29",
            "\"8.64e4\"", "1970-01-02",
            "-86400", "1969-12-31",
            "0.0000001", "1970-01-01");
        valid.forEach((value, expected) -> {
            var pack = parse(MANIFEST + SOURCE + "retrieved_on = " + value);
            assertThat(pack.policies().getFirst().sources().getFirst().retrievedOn().toString()).isEqualTo(expected);
        });
        for (String invalid : List.of("true", "00:00:00", "\"2023-02-29\"", "86400.25",
            "2024-02-29T00:00:00.000001Z", "9223372036854775807", "nan", "inf")) {
            assertThatThrownBy(() -> parse(MANIFEST + SOURCE + "retrieved_on = " + invalid))
                .hasMessageContaining("policies.0.sources.0.retrieved_on");
        }
    }

    @Test
    void rejectsMalformedTomlAndUtf8BeforeAcceptingAnyFields() {
        String malformed = MANIFEST + SOURCE + "revision = \"\\uD800\"\n";
        assertThatThrownBy(() -> parse(malformed)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("pack.toml TOML");
        byte[] invalidUtf8 = (MANIFEST + "# X").getBytes(StandardCharsets.UTF_8);
        invalidUtf8[invalidUtf8.length - 1] = (byte) 0xff;
        assertThatThrownBy(() -> PolicyPackManifest.parse(invalidUtf8))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> parse(MANIFEST.replace("schema_version = 1", "schema_version = 1.0")))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("schema_version");
    }
}
