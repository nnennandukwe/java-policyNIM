package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileInitializerTests {
    @TempDir
    Path workspace;

    @Test
    void createsThePortableTwoPackProfileBeforeItsDestinationDirectoryExists() throws Exception {
        var expected = installPacks();
        var file = workspace.resolve("profiles/team.toml");
        var profile = ProfileInitializer.initialize(file, List.of(workspace.resolve("team").toString(),
            workspace.resolve("base").toString()), "review");
        assertThat(Files.readString(file)).isEqualTo(expected.get("authoredProfile").textValue().replace("\n", System.lineSeparator()));
        assertThat(ProfileToml.read(file).json()).isEqualTo(profile.json());
        assertThat(ProfileResolver.resolve(file).list().get("effective_count").intValue()).isEqualTo(3);
    }

    @Test
    void writesAnInstalledAggregatePinThatWorksInANewDirectory() throws IOException {
        var output = workspace.resolve("new/dir/default.toml");
        var pin = ProfileInitializer.initialize(output, List.of(), "local").packs().getFirst();
        assertThat(pin.path()).isEqualTo("builtin:baseline");
        assertThat(pin.packId()).isEqualTo("policynim.baseline");
        assertThat(pin.digest()).isEqualTo("sha256:04a26d71f3b54eb63e41e779585177ce397767cdf89da9368de3e92d61667a26");
        assertThat(pin.policies()).hasSize(26);
        assertThat(ProfileResolver.resolve(output).validate().get("authored_count").intValue()).isEqualTo(26);
        if (output.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(output)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void refusesBadNamesAndPackSelectionsBeforeCreatingTheOutputParent() throws Exception {
        installPacks();
        var output = workspace.resolve("new/invalid.toml");
        var root = workspace.resolve("base").toString();
        assertThatThrownBy(() -> ProfileInitializer.initialize(output, List.of(root), "bad id"))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("profile ID");
        for (var packs : List.of(List.of(root, root), List.of(workspace.resolve("absent").toString()),
                List.of("builtin:baseline"), List.of("//remote.invalid/pack"))) {
            assertThatThrownBy(() -> ProfileInitializer.initialize(output, packs, "valid"))
                .isInstanceOf(InvalidPolicyDocumentException.class);
        }
        assertThat(output.getParent()).doesNotExist();
        ProfileInitializer.initialize(output, List.of(root), "valid");
        assertThat(ProfileResolver.resolve(output).validate().get("authored_count").intValue()).isEqualTo(2);
    }

    @Test
    void serializesQuotedKeysAndSpecialStringsAsTOML10() throws Exception {
        String specialId = "rule.\"\\\uD83E\uDD89";
        String relative = "team # \"quoted\"";
        String sha = "sha256:" + "a".repeat(64);
        var pin = new PolicyProfile.Pin(relative, "team.pack", "1.0.0", sha, Map.of(specialId, sha));
        var profile = new PolicyProfile("example", List.of(pin), List.of(), List.of(), List.of());
        var text = InitialProfileToml.render(profile);
        assertThat(text).contains("\uD83E\uDD89").contains("team # \\\"quoted\\\"");
        var output = workspace.resolve("quoted.toml");
        Files.writeString(output, text);
        assertThat(ProfileToml.read(output).json()).isEqualTo(profile.json());
    }

    @Test
    void preservesAnExistingProfileWhenTwoWritersRace() throws Exception {
        installPacks();
        var output = workspace.resolve("shared.toml");
        var start = new CountDownLatch(1);
        try (ExecutorService writers = Executors.newFixedThreadPool(2)) {
            var attempts = List.of("team", "base").stream().map(pack -> writers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("The start signal timed out.");
                }
                try {
                    ProfileInitializer.initialize(output, List.of(workspace.resolve(pack).toString()), pack);
                    return pack;
                } catch (InvalidPolicyDocumentException loser) {
                    assertThat(loser.getMessage()).contains("already exists");
                    return "";
                }
            })).toList();
            start.countDown();
            var first = attempts.getFirst().get(10, TimeUnit.SECONDS);
            var second = attempts.getLast().get(10, TimeUnit.SECONDS);
            assertThat(List.of(first, second)).containsOnlyOnce("");
            assertThat(ProfileToml.read(output).profileId()).isEqualTo(first + second);
            assertThat(ProfileResolver.resolve(output).validate().get("authored_count").intValue())
                .isEqualTo((first + second).equals("team") ? 1 : 2);
        }
        assertThat(workspace.toFile().list()).noneMatch(name -> name.startsWith(".policynim-profile-"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void preservesPackDirectoryNamesThatStartOrEndWithWhitespace() throws Exception {
        installPacks();
        var pack = Files.move(workspace.resolve("team"), workspace.resolve(" team "));
        var output = workspace.resolve("edge.toml");
        var profile = ProfileInitializer.initialize(output, List.of(pack.toString()), "edge");
        assertThat(profile.packs().getFirst().path()).isEqualTo("./ team /.");
        assertThat(ProfileToml.read(output).json()).isEqualTo(profile.json());
        assertThat(ProfileResolver.resolve(output).list().get("effective_count").intValue()).isEqualTo(1);
    }

    private JsonNode installPacks() throws IOException {
        var expected = new ObjectMapper().readTree(getClass().getResource("/parity/catalog/profile-authoring.json"));
        for (var entry : expected.required("files").properties()) {
            var file = workspace.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue().textValue());
        }
        return expected;
    }
}
