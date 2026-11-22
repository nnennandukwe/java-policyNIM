package io.policynim.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileCommandTests {
    private static final String BUILTIN_DIGEST =
        "sha256:19296547678340e555850c35725797ca5b9d601778e4a5eb125f7e6882dbb9a5";
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final ObjectMapper json = new ObjectMapper();

    private int run(String... args) {
        return ProfileCommand.run(args, new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8));
    }

    @Test
    void validatesAllSevenBuiltinPacksWithTheDirectProfileEnvelope(@TempDir Path dir) throws Exception {
        String name = "/io/policynim/bundled/policies/profiles/full-engineering.toml";
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(name))) {
            String source = new String(input.readAllBytes(), UTF_8).replace("path = \"../", "path = \"builtin:");
            Path profile = Files.writeString(dir.resolve("team.toml"), source, UTF_8);
            assertThat(run("profile", "validate", "--format", "json", profile.toString())).isZero();
            var result = json.readTree(out.toByteArray());
            assertThat(result.size()).isEqualTo(4);
            assertThat(result.required("status").textValue()).isEqualTo("ok");
            assertThat(result.required("catalog_digest").textValue()).isEqualTo(BUILTIN_DIGEST);
            assertThat(result.required("limits").textValue()).contains("declared conflicts");
            assertThat(result.required("effective_count").intValue()).isEqualTo(26);
            assertThat(err.size()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"preview", "validate"})
    void errorsUseOnlyStatusAndMessageInJson(String command, @TempDir Path dir) throws Exception {
        assertThat(run("profile", command, dir.resolve("missing.toml").toString(), "--format=json")).isOne();
        var error = json.readTree(out.toByteArray());
        assertThat(error.size()).isEqualTo(2);
        assertThat(error.required("status").textValue()).isEqualTo("error");
        assertThat(error.required("message").textValue()).isNotBlank();
        assertThat(err.size()).isZero();
    }

    @Test
    void initializesTheBundledProfileAndReportsItsCleanReviewDigest(@TempDir Path dir) throws Exception {
        Path profile = dir.resolve("a profile.toml");
        assertThat(run("profile", "init", dir.resolve(".").resolve(profile.getFileName()).toString(), "--format=json")).isZero();
        var created = json.readTree(out.toByteArray());
        assertThat(created.required("status").textValue()).isEqualTo("ok");
        assertThat(created.required("path").textValue()).isEqualTo(profile.toString());
        assertThat(created.at("/profile/profile_id").textValue()).isEqualTo("local");
        assertThat(created.at("/profile/packs/0/path").textValue()).isEqualTo("builtin:baseline");
        assertThat(created.at("/profile/packs/0/policies").size()).isEqualTo(26);
        byte[] before = Files.readAllBytes(profile);
        out.reset();
        assertThat(run("profile", "diff", "--format", "json", profile.toString())).isZero();
        var diff = json.readTree(out.toByteArray());
        assertThat(diff.size()).isEqualTo(10);
        assertThat(diff.required("review_digest").textValue()).isEqualTo("sha256:03608405e3f4d88f2522d1b2bcea206f9f6cc7ca94f8a4dddd5543080adaf85f");
        assertThat(diff.required("current_policies").size()).isEqualTo(26);
        assertThat(diff.required("validation_error").isNull()).isTrue();
        assertThat(diff.required("changed_packs").isEmpty()).isTrue();
        assertThat(Files.readAllBytes(profile)).isEqualTo(before);
        assertThat(err.size()).isZero();
    }

    @Test
    void initTextShowsTheDestinationWithNativeSeparators(@TempDir Path dir) {
        Path profile = dir.resolve("profiles").resolve("team.toml");
        assertThat(run("profile", "init", profile.toString())).isZero();
        assertThat(out.toString(UTF_8).lines().findFirst()).contains("Created pinned profile: " + profile);
    }

    @Test
    void diffKeepsMultilineNamesInJsonAndDisplaysOneLinePerTextField(@TempDir Path dir) throws Exception {
        Path profile = dir.resolve("team.toml");
        assertThat(run("profile", "init", profile.toString())).isZero();
        String digest = "sha256:" + "0".repeat(64);
        Files.writeString(profile, """
            "handoff\\nreview\\towner" = "%s"
            [[exclusions]]
            target_id = "handoff\\nreview\\towner"
            expected_digest = "%s"
            reason = "Review the handoff."
            """.formatted(digest, digest), StandardOpenOption.APPEND);

        out.reset();
        assertThat(run("profile", "diff", "--format=json", profile.toString())).isZero();
        var report = json.readTree(out.toByteArray());
        assertThat(report.at("/removed/0").textValue()).isEqualTo("handoff\nreview\towner");
        assertThat(report.at("/invalidated_decisions/0").textValue()).isEqualTo("handoff\nreview\towner");
        assertThat(report.required("validation_error").textValue())
            .isEqualTo("Unknown target 'handoff\\nreview\\towner'; review IDs.");

        out.reset();
        assertThat(run("profile", "diff", profile.toString())).isZero();
        assertThat(out.toString(UTF_8).lines()).hasSize(8).contains(
            "removed: handoff\\u000areview\\u0009owner",
            "invalidated_decisions: handoff\\u000areview\\u0009owner",
            "Update blocked: Unknown target 'handoff\\nreview\\towner'; review IDs.");
        assertThat(err.size()).isZero();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void initKeepsTheJsonErrorPathAndDisplaysItOnOneLine(@TempDir Path dir) throws Exception {
        Path pack = Files.createDirectory(dir.resolve("handoff\nreview\towner"));
        Path selector = Files.createSymbolicLink(dir.resolve("team-pack"), pack);
        Files.writeString(pack.resolve("pack.toml"), """
            schema_version = 1
            pack_id = "team"
            version = "1.0.0"
            [[policies]]
            policy_id = "handoff"
            path = "handoff.md"
            """);
        Files.writeString(pack.resolve("handoff.md"), "");
        String path = dir.resolve("team.toml").toString();
        String message = "Policy document " + pack.getFileName() + "/handoff.md does not contain usable Markdown content.";
        assertThat(run("profile", "init", path, "--pack", selector.toString(), "--format=json")).isOne();
        assertThat(json.readTree(out.toByteArray()).required("message").textValue()).isEqualTo(message);
        assertThat(err.size()).isZero();

        out.reset();
        assertThat(run("profile", "init", path, "--pack", selector.toString())).isOne();
        assertThat(out.size()).isZero();
        assertThat(err.toString(UTF_8).lines()).containsExactly(message.replace("\n", "\\u000a").replace("\t", "\\u0009"));
    }

    @Test
    void rejectsInitOnlyFlagsOnDiffAndKeepsDomainErrorsOnJsonStdout(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("existing.toml");
        byte[] original = "operator notes".getBytes(UTF_8);
        Files.write(path, original);
        assertThat(run("profile", "diff", path.toString(), "--pack", "elsewhere")).isEqualTo(2);
        assertThat(run("profile", "preview", path.toString(), "--id=test")).isEqualTo(2);
        assertThat(run("profile", "init", path.toString(), "--pack")).isEqualTo(2);
        err.reset();
        assertThat(run("profile", "init", path.toString(), "--format=json")).isOne();
        var error = json.readTree(out.toByteArray());
        assertThat(error.size()).isEqualTo(2);
        assertThat(error.required("message").textValue()).contains("already exists");
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
        assertThat(err.size()).isZero();
    }

    @Test
    void consumesTheNextTokenAsAnExplicitOptionValue(@TempDir Path dir) {
        Path output = dir.resolve("review.toml");
        assertThat(run("profile", "init", output.toString(), "--id")).isEqualTo(2);
        err.reset();
        assertThat(run("profile", "init", output.toString(), "--id", "--help")).isOne();
        assertThat(err.toString(UTF_8)).contains("Invalid profile ID").doesNotContain("Usage:");
        assertThat(out.size()).isZero();
        assertThat(output).doesNotExist();
    }

    @Test
    void helpAndUsageErrorsPrecedeProfileLookup() {
        assertThat(run("profile", "preview", "--help")).isZero();
        assertThat(out.toString(UTF_8)).contains("preview", "validate", "--format");
        out.reset();
        assertThat(run("profile", "validate", "--format=json")).isEqualTo(2);
        assertThat(run("profile", "preview", "missing.toml", "--format=xml")).isEqualTo(2);
        assertThat(run("profile", "preview", "one.toml", "two.toml")).isEqualTo(2);
        assertThat(out.size()).isZero();
        assertThat(err.toString(UTF_8)).contains("Usage", "profile");
    }
}
