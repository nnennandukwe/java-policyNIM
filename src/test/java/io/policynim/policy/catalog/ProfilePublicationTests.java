package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfilePublicationTests {
    private Path workspace;

    @BeforeEach
    void selectPublicationDirectory(@TempDir Path directory) throws IOException {
        workspace = directory.toRealPath();
    }

    private static void publish(ProfilePublication publisher, Path output, String contents) throws IOException {
        try (var destination = ProfileDestination.select(output)) {
            publisher.publish(destination, contents);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolvesAParentComponentAfterFollowingASelectedDirectoryAlias() throws IOException {
        var actual = Files.createDirectories(workspace.resolve("sources/reviews/accepted"));
        var alias = Files.createSymbolicLink(workspace.resolve("accepted"), actual);
        var input = alias.resolve("../drafts/team.toml");
        var expected = actual.getParent().resolve("drafts/team.toml");

        try (var destination = ProfileDestination.select(input)) {
            assertThat(destination.path()).isEqualTo(expected);
            assertThat(expected.getParent()).doesNotExist();
            new ProfilePublication().publish(destination, "Reviewed profile.");
        }

        assertThat(Files.isSameFile(input, expected)).isTrue();
        assertThat(Files.readString(input)).isEqualTo("Reviewed profile.");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void reportsWhenTheSelectedAncestorMovesBeforeNestedPublication() throws IOException {
        var original = Files.createDirectory(workspace.resolve("reviews"));
        var moved = workspace.resolve("archived-reviews");
        var output = original.resolve("new/nested/profile.toml");

        try (var destination = ProfileDestination.select(output)) {
            assertThat(original.resolve("new")).doesNotExist();
            Files.move(original, moved);
            assertThatThrownBy(() -> new ProfilePublication().publish(destination, "Reviewed profile."))
                .isInstanceOf(InvalidPolicyDocumentException.class)
                .hasMessageContaining("selected profile directory").hasMessageContaining("retry");
        }

        assertThat(original).doesNotExist();
        assertThat(moved).isEmptyDirectory();
        var recovered = moved.resolve("new/nested/profile.toml");
        publish(new ProfilePublication(), recovered, "Recovered.");
        assertThat(Files.readString(recovered)).isEqualTo("Recovered.");
    }

    @Test
    void refusesAVirtualFilesystemBeforeCreatingItsOutputParent() throws IOException {
        var zip = URI.create("jar:" + workspace.resolve("profiles.zip").toUri());
        try (var fs = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path output = fs.getPath("/profiles/new.toml");
            assertThatThrownBy(() -> publish(new ProfilePublication(), output, "Profile."))
                .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("local filesystem");
            assertThat(Files.exists(output.getParent())).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "force", "link"})
    void abortsBeforePublishingAndAllowsAFreshAttempt(String failure) throws IOException {
        var output = workspace.resolve("profile.toml");
        var publisher = new ProfilePublication() {
            @Override
            void write(ProfileStage stage, byte[] bytes) throws IOException {
                if (failure.equals("write")) {
                    stage.write(new byte[]{'x'});
                    throw new IOException("write");
                }
                super.write(stage, bytes);
            }

            @Override
            void force(ProfileStage stage) throws IOException {
                if (failure.equals("force")) {
                    throw new IOException("force");
                }
                super.force(stage);
            }

            @Override
            void commit(ProfileStage stage, String outputName) throws IOException {
                if (failure.equals("link")) {
                    throw new IOException("link");
                }
                super.commit(stage, outputName);
            }
        };
        assertThatThrownBy(() -> publish(publisher, output, "repaired")).isInstanceOf(IOException.class)
            .hasMessage(failure);
        assertThat(workspace.toFile().list()).isEmpty();
        publish(new ProfilePublication(), output, "repaired");
        assertThat(Files.readString(output)).isEqualTo("repaired");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void distinguishesARelocatedPublishedDirectoryFromAFailedCommit() throws IOException {
        var selected = Files.createDirectory(workspace.resolve("selected"));
        var relocated = workspace.resolve("relocated");
        var publisher = new ProfilePublication() {
            @Override
            void commit(ProfileStage stage, String outputName) throws IOException {
                Files.move(selected, relocated);
                super.commit(stage, outputName);
            }
        };
        assertThatThrownBy(() -> publish(publisher, selected.resolve("profile.toml"), "Reviewed profile."))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("profile was created");
        assertThat(Files.readString(relocated.resolve("profile.toml"))).isEqualTo("Reviewed profile.");
        assertThat(selected).doesNotExist();
        assertThat(relocated.toFile().list()).containsExactly("profile.toml");
    }

    @Test
    void releasesItsStageAfterTheFirstIdentityCheckFailsAndCanRetry() throws IOException {
        var output = workspace.resolve("new.toml");
        var publisher = new ProfilePublication() {
            @Override
            void verify(ProfileStage stage) throws IOException {
                throw new IOException("Temporary identity lookup failure.");
            }
        };
        assertThatThrownBy(() -> publish(publisher, output, "Complete profile.")).isInstanceOf(IOException.class)
            .hasMessageContaining("identity lookup");
        assertThat(workspace.toFile().list()).isEmpty();
        publish(new ProfilePublication(), output, "Complete profile.");
        assertThat(Files.readString(output)).isEqualTo("Complete profile.");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void preservesAnUnexpectedFileWhenAWriteIsInterrupted() throws IOException {
        var output = workspace.resolve("profile.toml");
        var publisher = new ProfilePublication() {
            @Override
            void write(ProfileStage stage, byte[] bytes) throws IOException {
                super.write(stage, bytes);
                Path privateDirectory;
                try (var entries = Files.list(workspace)) {
                    privateDirectory = entries.findFirst().orElseThrow();
                }
                Path stagedFile = privateDirectory.resolve("profile");
                Files.move(stagedFile, privateDirectory.resolve("earlier"));
                Files.writeString(stagedFile, "Unrelated file.");
                throw new IOException("Interrupted write.");
            }
        };
        assertThatThrownBy(() -> publish(publisher, output, "Complete profile."))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("unverified staging entry");
        assertThat(output).doesNotExist();
        try (var entries = Files.list(workspace)) {
            Path retained = entries.findFirst().orElseThrow();
            assertThat(Files.readString(retained.resolve("profile"))).isEqualTo("Unrelated file.");
            assertThat(Files.readString(retained.resolve("earlier"))).isEqualTo("Complete profile.");
        }
        publish(new ProfilePublication(), output, "Recovered.");
        assertThat(Files.readString(output)).isEqualTo("Recovered.");
    }

    @Test
    void reportsTheCreatedProfileWhenStageCleanupFails() throws IOException {
        var output = workspace.resolve("profiles/team.toml");
        var fixture = new ObjectMapper().readTree(getClass().getResource("/parity/catalog/profile-authoring.json"));
        for (var entry : fixture.required("files").properties()) {
            var file = workspace.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue().textValue());
        }
        var publisher = new ProfilePublication() {
            @Override
            void discard(ProfileStage stage) throws IOException {
                throw new IOException("Temporary cleanup failure.");
            }
        };
        String contents = fixture.required("authoredProfile").textValue();
        assertThatThrownBy(() -> publish(publisher, output, contents)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("profile was created").hasMessageContaining("Inspect");
        assertThat(Files.readString(output)).isEqualTo(contents);
        assertThat(ProfileResolver.resolve(output).list().get("authored_count").intValue()).isEqualTo(3);
        assertThatThrownBy(() -> publish(new ProfilePublication(), output, "another profile"))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("already exists");
        assertThat(Files.readString(output)).isEqualTo(contents);
    }

    @ParameterizedTest
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @ValueSource(booleans = {false, true})
    void leavesAReplacedStageOrRenamedParentUntouched(boolean renameParent) throws IOException {
        Path parent = Files.createDirectory(workspace.resolve("profiles"));
        Path output = parent.resolve("profile.toml");
        var publisher = new ProfilePublication() {
            @Override
            void force(ProfileStage handle) throws IOException {
                super.force(handle);
                Path stage;
                try (var paths = Files.list(parent)) {
                    stage = paths.findFirst().orElseThrow();
                }
                if (renameParent) {
                    Files.move(parent, workspace.resolve("earlier-directory"));
                    Files.createDirectory(parent);
                } else {
                    Files.move(stage, parent.resolve("earlier-file"));
                }
                Files.writeString(stage, "Unrelated content.");
            }
        };
        assertThatThrownBy(() -> publish(publisher, output, "Complete initial profile."))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("changed");
        assertThat(output).doesNotExist();
        try (var names = Files.list(parent)) {
            var remaining = names.filter(path -> path.getFileName().toString().startsWith(".policynim-profile-")).toList();
            assertThat(remaining).hasSize(1);
            assertThat(Files.readString(remaining.getFirst())).isEqualTo("Unrelated content.");
        }
        publish(new ProfilePublication(), output, "Complete initial profile.");
        assertThat(Files.readString(output)).isEqualTo("Complete initial profile.");
    }
}
