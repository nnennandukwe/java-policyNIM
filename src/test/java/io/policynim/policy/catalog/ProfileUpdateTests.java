package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileUpdateTests {
    @TempDir Path workspace;
    private Path profile;
    private String token;

    @BeforeEach
    void installReviewedFileAndChangedSources() throws IOException {
        try (var input = getClass().getResourceAsStream("/parity/catalog/profile-authoring.json")) {
            var fixture = new ObjectMapper().readTree(input);
            install(fixture.required("files"));
            profile = workspace.resolve(fixture.required("profileDestination").asText());
            Files.createDirectories(profile.getParent());
            Files.writeString(profile, fixture.required("authoredProfile").asText());
            install(fixture.required("edit"));
            token = fixture.required("editExpected").required("review_digest").asText();
        }
    }

    private void install(JsonNode files) throws IOException {
        for (var entry : files.properties()) {
            Path path = workspace.resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue().asText());
        }
    }

    @Test
    void returnsReviewedDiffAndRefusesPriorTokenAfterCommit() throws Exception {
        var reviewed = ProfileResolver.diff(profile);
        assertThat(reviewed.required("review_digest").asText()).isEqualTo(token);
        assertThat(ProfileUpdate.apply(profile, token)).isEqualTo(reviewed);
        assertThat(ProfileResolver.diff(profile).required("changed_packs").isEmpty()).isTrue();
        assertThat(ProfileResolver.resolve(profile).validate().required("authored_count").intValue()).isEqualTo(3);
        assertThatThrownBy(() -> ProfileUpdate.apply(profile, token))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("Review digest changed");
        assertThat(profile.getParent().toFile().list()).containsExactlyInAnyOrder("team.toml", ".team.toml.lock");
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "mode", "force", "verify", "replace"})
    void precommitFailuresPreserveOriginalAndAllowRetry(String failurePoint) throws Exception {
        byte[] original = Files.readAllBytes(profile);
        var refusal = assertThatThrownBy(() -> updateAt(point -> {
            if (point.equals(failurePoint) || point.equals("discard")) {
                throw new IOException("test " + point);
            }
        }));
        refusal.isInstanceOf(InvalidPolicyDocumentException.class)
            .hasCauseInstanceOf(IOException.class)
            .cause().hasMessage("test " + failurePoint)
            .satisfies(cause -> assertThat(cause.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("test discard"));
        assertThat(Files.readAllBytes(profile)).isEqualTo(original);
        assertThat(profile.getParent().toFile().list()).containsExactlyInAnyOrder("team.toml", ".team.toml.lock");
        assertThat(ProfileUpdate.apply(profile, token).required("warnings").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "source"})
    void rechecksOrdinaryEditsAfterForcingTheStage(String changed) throws Exception {
        Path edited = changed.equals("profile") ? profile : workspace.resolve("base/A.md");
        var original = Files.readString(profile);
        String note = "\n# operator edit\n";
        assertThatThrownBy(() -> updateAt(point -> {
            if (point.equals("force")) {
                Files.writeString(edited, note, StandardOpenOption.APPEND);
            }
        })).isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("Profile or sources changed");
        assertThat(Files.readString(profile)).isEqualTo(changed.equals("profile") ? original + note : original);
        assertThat(Files.readString(edited)).endsWith(note);
        var fresh = ProfileResolver.diff(profile);
        assertThat(ProfileUpdate.apply(profile, fresh.required("review_digest").asText())).isEqualTo(fresh);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "discard", "both"})
    void reportsOrderedPostcommitWarningsWithThePublishedPins(String failure) throws IOException {
        var report = updateAt(point -> {
            if (point.equals(failure) || (failure.equals("both") && List.of("sync", "discard").contains(point))) {
                throw new IOException("test " + point);
            }
        });
        String sync = "Profile pins changed, but directory sync failed. Reread the profile before retrying or restarting.";
        String cleanup = "Profile staging cleanup couldn't finish. Preserve unexpected entries "
            + "and inspect the profile before retrying.";
        var expected = switch (failure) {
            case "sync" -> List.of(sync);
            case "discard" -> List.of(cleanup);
            default -> List.of(sync, cleanup);
        };
        assertThat(report.required("warnings")).extracting(JsonNode::asText).containsExactlyElementsOf(expected);
        assertThat(report.required("review_digest").asText()).isEqualTo(token);
        assertThat(ProfileResolver.diff(profile).required("changed_packs").isEmpty()).isTrue();
    }

    @Test
    void interruptedWriterDiscardsItsStageAndReleasesTheLock() throws IOException {
        byte[] original = Files.readAllBytes(profile);
        try {
            assertThatThrownBy(() -> updateAt(point -> {
                if (point.equals("force")) {
                    Thread.currentThread().interrupt();
                }
            })).isInstanceOf(InvalidPolicyDocumentException.class)
                .hasMessageContaining("Profile update was interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(Files.readAllBytes(profile)).isEqualTo(original);
        assertThat(profile.getParent().toFile().list()).containsExactlyInAnyOrder("team.toml", ".team.toml.lock");
        assertThat(ProfileUpdate.apply(profile, token).required("review_digest").asText()).isEqualTo(token);
    }

    @Test
    void serializesTwoUpdatesUsingTheSameReviewToken() throws Exception {
        var start = new CountDownLatch(1);
        try (var writers = Executors.newFixedThreadPool(2)) {
            var attempts = List.of(1, 2).stream().map(writer -> writers.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    return ProfileUpdate.apply(profile, token).required("review_digest").asText();
                } catch (InvalidPolicyDocumentException refusal) {
                    if (!refusal.getMessage().contains("Review digest changed")) {
                        throw refusal;
                    }
                    return refusal.getMessage();
                }
            })).toList();
            start.countDown();
            var results = List.of(attempts.getFirst().get(10, TimeUnit.SECONDS),
                attempts.getLast().get(10, TimeUnit.SECONDS));
            assertThat(results).containsOnlyOnce(token).anyMatch(error -> error.contains("Review digest changed"));
        }
        assertThat(ProfileResolver.diff(profile).required("changed_packs").isEmpty()).isTrue();
    }

    @FunctionalInterface
    private interface Checkpoint {
        void reached(String point) throws IOException;
    }

    private JsonNode updateAt(Checkpoint checkpoint) {
        return ProfileUpdate.apply(profile, token, (parent, name) ->
            new ObservedFiles(ProfileUpdate.open(parent, name), checkpoint));
    }

    private record ObservedFiles(ProfileUpdateFiles files, Checkpoint checkpoint) implements ProfileUpdateFiles {
        @Override
        public Original readOriginal() throws IOException {
            return files.readOriginal();
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            files.write(bytes);
            checkpoint.reached("write");
        }

        @Override
        public void preserveMode(int mode) throws IOException {
            files.preserveMode(mode);
            checkpoint.reached("mode");
        }

        @Override
        public void force() throws IOException {
            files.force();
            checkpoint.reached("force");
        }

        @Override
        public void verify(Original original) throws IOException {
            files.verify(original);
            checkpoint.reached("verify");
        }

        @Override
        public void replace() throws IOException {
            checkpoint.reached("replace");
            files.replace();
        }

        @Override
        public void syncDirectory() throws IOException {
            files.syncDirectory();
            checkpoint.reached("sync");
        }

        @Override
        public void discard() throws IOException {
            files.discard();
            checkpoint.reached("discard");
        }

        @Override
        public void close() throws IOException {
            files.close();
        }
    }
}
