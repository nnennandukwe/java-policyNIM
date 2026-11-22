package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfilePublicationTests {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"write", "force", "link"})
    void abortsBeforePublishingAndAllowsAFreshAttempt(String failure) throws IOException {
        var output = workspace.resolve("profile.toml");
        var publisher = new ProfilePublication() {
            @Override
            void write(FileChannel file, ByteBuffer bytes) throws IOException {
                if (failure.equals("write")) {
                    file.write(ByteBuffer.wrap(new byte[]{'x'}));
                    throw new IOException("write");
                }
                super.write(file, bytes);
            }

            @Override
            void force(FileChannel file) throws IOException {
                if (failure.equals("force")) {
                    throw new IOException("force");
                }
                super.force(file);
            }

            @Override
            void link(Path target, Path stage) throws IOException {
                if (failure.equals("link")) {
                    throw new IOException("link");
                }
                super.link(target, stage);
            }
        };
        assertThatThrownBy(() -> publisher.publish(output, "repaired")).isInstanceOf(IOException.class)
            .hasMessage(failure);
        assertThat(workspace.toFile().list()).isEmpty();
        new ProfilePublication().publish(output, "repaired");
        assertThat(Files.readString(output)).isEqualTo("repaired");
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
            void discard(Path stage) throws IOException {
                throw new IOException("Temporary cleanup failure.");
            }
        };
        String contents = fixture.required("authoredProfile").textValue();
        assertThatThrownBy(() -> publisher.publish(output, contents)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("profile was created").hasMessageContaining("Inspect");
        assertThat(Files.readString(output)).isEqualTo(contents);
        assertThat(ProfileResolver.resolve(output).list().get("authored_count").intValue()).isEqualTo(3);
        assertThatThrownBy(() -> new ProfilePublication().publish(output, "another profile"))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("already exists");
        assertThat(Files.readString(output)).isEqualTo(contents);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leavesAReplacedStageOrRenamedParentUntouched(boolean renameParent) throws IOException {
        Path parent = Files.createDirectory(workspace.resolve("profiles"));
        Path output = parent.resolve("profile.toml");
        var publisher = new ProfilePublication() {
            @Override
            void force(FileChannel file) throws IOException {
                super.force(file);
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
        assertThatThrownBy(() -> publisher.publish(output, "Complete initial profile."))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("changed");
        assertThat(output).doesNotExist();
        try (var names = Files.list(parent)) {
            var remaining = names.filter(path -> path.getFileName().toString().startsWith(".policynim-profile-")).toList();
            assertThat(remaining).hasSize(1);
            assertThat(Files.readString(remaining.getFirst())).isEqualTo("Unrelated content.");
        }
        new ProfilePublication().publish(output, "Complete initial profile.");
        assertThat(Files.readString(output)).isEqualTo("Complete initial profile.");
    }
}
