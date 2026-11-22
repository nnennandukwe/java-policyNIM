package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
