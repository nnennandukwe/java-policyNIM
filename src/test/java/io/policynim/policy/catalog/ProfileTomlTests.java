package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileTomlTests {
    private static final String HASH = "sha256:" + "0".repeat(64);
    private static final String PROFILE = """
        schema_version = 1
        profile_id = "test"
        [[packs]]
        path = "builtin:api"
        pack_id = "example"
        version = "1.0.0"
        digest = "%s"
        [packs.policies]
        "policy.b" = "%s"
        "policy.a" = "%s"
        """.formatted(HASH, HASH, HASH);

    @TempDir Path temporary;

    @Test
    void validatesEveryFieldBeforeOpeningAnyPackAndReportsOnlyLocations() throws Exception {
        Path file = temporary.resolve("profile.toml");
        String invalid = PROFILE.replace("schema_version = 1", "schema_version = true")
            .replace("\"builtin:api\"", "\"https://private.invalid/SECRET-VALUE\"")
            .replace("\"1.0.0\"", "\"01.0.0\"") + """
            [[exclusions]]
            target_id = "test"
            expected_digest = 124
            reason = "   "
            """;
        Files.writeString(file, invalid);
        assertThatThrownBy(() -> ProfileResolver.resolve(file)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("schema_version, packs.0.path, packs.0.version, exclusions.0.expected_digest, exclusions.0.reason")
            .hasMessageNotContaining("SECRET-VALUE").hasMessageNotContaining("private.invalid");
        Files.writeString(file, PROFILE);
        var profile = ProfileToml.read(file);
        assertThat(profile.packs().getFirst().policies().keySet()).containsExactly("policy.a", "policy.b");
    }

    @Test
    void usesProfileTextWhitespaceForDecisionsAndLocalSelectors() throws Exception {
        Path file = temporary.resolve("profile.toml");
        String control = "\\" + "u001c";
        Files.writeString(file, PROFILE + """
            [[exclusions]]
            target_id = "   test "
            expected_digest = "%s"
            reason = "%sReview%s"
            """.formatted(HASH, control, control));
        var decision = ProfileToml.read(file).exclusions().getFirst();
        assertThat(decision.targetId()).isEqualTo("test");
        assertThat(decision.reason()).isEqualTo("\u001cReview\u001c");

        Files.writeString(file, PROFILE.replace("builtin:api", control + "local"));
        assertThatThrownBy(() -> ProfileToml.read(file)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("packs.0.path");
    }

    @Test
    void rejectsMalformedTomlAndUtf8WithThePublicProfileError() throws Exception {
        Path file = temporary.resolve("profile.toml");
        Files.write(file, new byte[] {(byte) 0xff});
        assertThatThrownBy(() -> ProfileToml.read(file)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageStartingWith("Couldn't read profile TOML.");
        Files.writeString(file, "[unclosed");
        assertThatThrownBy(() -> ProfileToml.read(file)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageStartingWith("Couldn't read profile TOML.");
    }
}
