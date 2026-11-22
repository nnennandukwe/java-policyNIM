package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileResolverTests {
    @TempDir Path temporary;

    @Test
    void resolvesTheSevenBundledTopicPinsFromAnExternalProfile() throws Exception {
        var resource = "/io/policynim/bundled/policies/profiles/full-engineering.toml";
        String profileText;
        try (var profile = getClass().getResourceAsStream(resource)) {
            profileText = new String(profile.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path file = temporary.resolve("profile.toml");
        Files.writeString(file, profileText.replace("path = \"../", "path = \"builtin:"));
        var catalog = ProfileResolver.resolve(file);
        var listing = catalog.list();
        assertThat(listing.get("digest").textValue()).isEqualTo(
            "sha256:19296547678340e555850c35725797ca5b9d601778e4a5eb125f7e6882dbb9a5");
        assertThat(listing.get("profile_digest").textValue()).isEqualTo(
            "sha256:1f3e011f97cbb4fd36dcf03f28d4d0e2a3c9968f53d179cb368d6df7c1ee4ecd");
        assertThat(listing.get("pack_pins")).hasSize(7);
        assertThat(listing.get("policies")).hasSize(26);
        assertThat(listing.at("/pack_pins/0/path").textValue()).isEqualTo("builtin:security");
    }

    @Test
    void decisionsKeepAuthoredPolicyBodiesAndStandaloneTargets() throws Exception {
        var fixture = installDecisionFixture();
        var catalog = ProfileResolver.resolve(temporary.resolve("team.toml"));
        assertThat(catalog.list()).isEqualTo(fixture.get("catalog"));
        assertThat(catalog.show("A").get("state").textValue()).isEqualTo("replaced");
        assertThat(catalog.validate().get("effective_count").intValue()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidDecisionsAndRereadsTheCorrectedFile() throws Exception {
        var fixture = installDecisionFixture();
        Path file = temporary.resolve("team.toml");
        String original = Files.readString(file);
        reject(file, original.replace("target_id = \"B\"", "target_id = \"A\""), "Contradictory exclusions");
        reject(file, original.replace("replacement_id = \"D\"", "replacement_id = \"B\""), "Replacement chains");
        reject(file, original.replace("replacement_id = \"D\"", "replacement_id = \"E\""), "Unknown replacement ID");
        reject(file, original.replace("left = \"A\"", "left = \"C\""), "Declared conflict: C / D");
        reject(file, original.replace("5da30e53", "00000000"), "Stale target digest for 'B'");
        reject(file, original.replace("41374b80", "00000000"), "doesn't match its pin");
        Files.writeString(file, original);
        assertThat(ProfileResolver.resolve(file).list()).isEqualTo(fixture.get("catalog"));
    }

    @Test
    void refusesRepeatedDirectoryBeforeItCanSatisfyAnotherPackPin() throws Exception {
        installDecisionFixture();
        Path file = temporary.resolve("team.toml");
        String original = Files.readString(file);
        reject(file, original.replace("path = \"team\"", "path = \"./base\""), "A local pack directory was selected more than once.");
        reject(file, original.replace("review.team", "review.base"), "Each pack_id must appear once.");
    }

    @Test
    void comparesOnlyEffectiveRuntimeMatchersAfterNormalizingHostCase() throws Exception {
        Path pack = Files.createDirectory(temporary.resolve("rules"));
        StringBuilder manifest = new StringBuilder("schema_version = 1\npack_id = \"local.rules\"\nversion = \"1.0.0\"\n");
        writeRuntimeMember(pack, manifest, "A", "EXAMPLE.ORG", "block");
        writeRuntimeMember(pack, manifest, "B", "example.org", "confirm");
        writeRuntimeMember(pack, manifest, "C", "other.example", "confirm");
        Files.writeString(pack.resolve("pack.toml"), manifest);
        // The pinned wheel's standalone entries and the fixture's original UTF-8 bytes supply these pins.
        String profile = """
            schema_version = 1
            profile_id = "runtime"
            [[packs]]
            path = "rules"
            pack_id = "local.rules"
            version = "1.0.0"
            digest = "sha256:3bc2f9232d3b7a95b589791e0bfa8704ce2bfd558fb15d5c8cbac230758c7fcd"
            [packs.policies]
            A = "sha256:7256b2d28f43c73cdc69b998b05a16e54ba4e36d51ede5c35c543f369e95df99"
            B = "sha256:93ebedfdeaf2480b078e3a762c4ba9f6afb9b06662fcbaa3fbc246b372d89755"
            C = "sha256:edbb73b1ffc6dd1ac8070b6a1fbb3f65f19996956f636850471468a8c4ef3017"
            """;
        Path file = temporary.resolve("profile.toml");
        reject(file, profile, "Conflicting runtime effects for identical matchers");
        Files.writeString(file, profile + """
            [[exclusions]]
            target_id = "A"
            expected_digest = "sha256:51bd4786386117d825d0741d93e085e6245eca50a31a19bcd1301b1dbc645881"
            reason = "Review the confirmed action."
            """);
        assertThat(ProfileResolver.resolve(file).validate().get("effective_count").intValue()).isEqualTo(2);
    }

    @Test
    void diffHashesTheAuthoredTextAndTheCurrentModelSeparately() throws Exception {
        installDecisionFixture();
        Path file = temporary.resolve("team.toml");
        String original = Files.readString(file);
        String cleanDigest = "sha256:4887d9450ad8f944b7d4a91a38c854311021269e31d68510cde1765f1d21040a";
        assertThat(ProfileResolver.diff(file).get("review_digest").asText()).isEqualTo(cleanDigest);
        Files.writeString(file, original.replace("\n", "\r\n"));
        assertThat(ProfileResolver.diff(file).get("review_digest").asText()).isEqualTo(cleanDigest);
        Files.writeString(file, "# operator note\n" + original);
        assertThat(ProfileResolver.diff(file).get("review_digest").asText())
            .isEqualTo("sha256:4319684014355bf8a728d8e941ae148401d77a52874bfe44a9f2158de06cab8c");

        String reordered = original.replaceFirst("(?m)^(A = [^\n]+)\n(B = [^\n]+)$", "$2\n$1");
        Files.writeString(file, reordered);
        assertThat(ProfileResolver.diff(file).get("review_digest").asText())
            .isEqualTo("sha256:feb1ab8afc02c5b5032679f0ca9c9d38c12564eeb75943660e3b701d78c2c44e");
        assertThat(Files.readString(file)).isEqualTo(reordered);
    }

    @Test
    void diffRetainsAllActiveSourceEntriesWhenDecisionValidationFailsPartway() throws Exception {
        var fixture = installDecisionFixture();
        Path file = temporary.resolve("team.toml");
        var report = ProfileResolver.diff(file);
        assertThat(report.get("validation_error").isNull()).isTrue();
        assertThat(report.get("changed_packs")).isEmpty();
        var expectedEntries = fixture.at("/catalog/policies").deepCopy();
        for (var entry : expectedEntries) {
            ((ObjectNode) entry).put("state", "active")
                .putNull("reason").putNull("replacement_id");
        }
        assertThat(report.get("current_policies")).isEqualTo(expectedEntries);

        Path changed = temporary.resolve("base/A.md");
        String source = Files.readString(changed);
        Files.writeString(changed, source + "Updated requirement.\n");
        var blocked = ProfileResolver.diff(file);
        assertThat(blocked.get("validation_error").asText()).startsWith("Stale target digest for 'A'");
        assertThat(blocked.get("changed").toString()).isEqualTo("[\"A\"]");
        assertThat(blocked.get("changed_packs").toString()).isEqualTo("[\"review.base\"]");
        assertThat(blocked.get("invalidated_decisions").toString()).isEqualTo("[\"A\"]");
        // The preceding valid exclusion of B still leaves B active in the source report.
        assertThat(blocked.at("/current_policies/1")).isEqualTo(expectedEntries.get(1));
        assertThat(blocked.at("/current_policies/0/body").asText()).contains("Updated requirement.");
        Files.writeString(changed, source);
        assertThat(ProfileResolver.diff(file)).isEqualTo(report);
    }

    @Test
    void diffReturnsDuplicateSourceEntriesInPackOrderAndRefusesChangedPackIdentity() throws Exception {
        installDecisionFixture();
        Path file = temporary.resolve("team.toml");
        Path manifest = temporary.resolve("team/pack.toml");
        String original = Files.readString(manifest);
        Files.copy(temporary.resolve("base/B.md"), temporary.resolve("team/B.md"));
        Files.writeString(manifest, original + "[[policies]]\npolicy_id = \"B\"\npath = \"B.md\"\n");
        var report = ProfileResolver.diff(file);
        assertThat(report.get("validation_error").asText()).isEqualTo("Duplicate effective policy IDs across selected packs.");
        assertThat(report.get("current_policies").findValuesAsText("path"))
            .containsExactly("review.base/A.md", "review.base/B.md", "review.team/B.md", "review.base/C.md", "review.team/D.md");
        assertThat(report.get("added").toString()).isEqualTo("[\"B\"]");
        assertThat(report.get("invalidated_decisions").toString()).isEqualTo("[\"B\"]");

        Files.writeString(manifest, original.replace("review.team", "other.team"));
        Files.delete(temporary.resolve("team/B.md"));
        assertThatThrownBy(() -> ProfileResolver.diff(file)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessage("Selected pack_id changed; review the profile selection.");
        Files.writeString(manifest, original);
        assertThat(ProfileResolver.diff(file).get("validation_error").isNull()).isTrue();
    }

    @Test
    void diffTracksRevisionsByPackAndPolicyWhenAPolicyMovesAndVersionChanges() throws Exception {
        installDecisionFixture();
        Path profile = temporary.resolve("team.toml");
        Path baseManifest = temporary.resolve("base/pack.toml");
        Path teamManifest = temporary.resolve("team/pack.toml");
        String base = Files.readString(baseManifest);
        String team = Files.readString(teamManifest);
        Files.writeString(baseManifest, base.replace("1.0.0", "1.0.1"));
        var changedVersion = ProfileResolver.diff(profile);
        assertThat(changedVersion.get("changed").toString()).isEqualTo("[\"A\",\"B\",\"C\"]");
        assertThat(changedVersion.get("changed_packs").toString()).isEqualTo("[\"review.base\"]");
        assertThat(changedVersion.get("invalidated_decisions").toString()).isEqualTo("[\"A\",\"B\"]");

        String member = "[[policies]]\npolicy_id = \"C\"\npath = \"C.md\"\n";
        Files.move(temporary.resolve("base/C.md"), temporary.resolve("team/C.md"));
        Files.writeString(baseManifest, base.replace(member, ""));
        Files.writeString(teamManifest, team + member);
        var moved = ProfileResolver.diff(profile);
        assertThat(moved.get("added").toString()).isEqualTo("[\"C\"]");
        assertThat(moved.get("removed").toString()).isEqualTo("[\"C\"]");
        assertThat(moved.get("changed")).isEmpty();
        assertThat(moved.get("changed_packs").toString()).isEqualTo("[\"review.base\",\"review.team\"]");
        assertThat(moved.get("validation_error").isNull()).isTrue();
    }

    private JsonNode installDecisionFixture() throws Exception {
        var fixture = new ObjectMapper().readTree(getClass().getResource("/parity/catalog/profile-decisions.json"));
        for (var member : fixture.get("files").properties()) {
            var file = temporary.resolve(member.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, member.getValue().textValue());
        }
        return fixture;
    }

    private static void reject(Path file, String content, String message) throws Exception {
        Files.writeString(file, content);
        assertThatThrownBy(() -> ProfileResolver.resolve(file)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining(message);
    }

    private static void writeRuntimeMember(Path pack, StringBuilder manifest, String id, String host, String effect)
            throws Exception {
        manifest.append("[[policies]]\npolicy_id = \"%s\"\npath = \"%s.md\"\n".formatted(id, id));
        Files.writeString(pack.resolve(id + ".md"), """
            ---
            policy_id: %s
            runtime_rules:
              - action: http_request
                effect: %s
                reason: Review %s.
                url_host_patterns:
                  - "%s"
            ---
            # Policy %s
            """.formatted(id, effect, id, host, id));
    }
}
