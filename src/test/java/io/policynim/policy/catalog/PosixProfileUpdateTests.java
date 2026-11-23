package io.policynim.policy.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledOnOs({OS.LINUX, OS.MAC})
class PosixProfileUpdateTests {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"rw-------", "rw-r-----", "rw-r--r--"})
    void preservesExistingModeAndReadsUniversalNewlines(String mode) throws Exception {
        Path profile = workspace.resolve("profile.toml");
        Files.writeString(profile, "original\r\nprofile\r");
        var permissions = PosixFilePermissions.fromString(mode);
        Files.setPosixFilePermissions(profile, permissions);
        try (var files = open(); Closeable cleanup = files::discard) {
            var original = files.readOriginal();
            assertThat(original.text()).isEqualTo("original\nprofile\n");
            files.write("updated".getBytes(StandardCharsets.UTF_8));
            files.preserveMode(original.mode());
            files.force();
            files.verify(original);
            files.replace();
            files.syncDirectory();
        }
        assertThat(Files.readString(profile)).isEqualTo("updated");
        assertThat(Files.getPosixFilePermissions(profile)).isEqualTo(permissions);
        assertThat(Files.getPosixFilePermissions(workspace.resolve(".profile.toml.lock")))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(workspace.toFile().list()).containsExactlyInAnyOrder("profile.toml", ".profile.toml.lock");
    }

    @Test
    void refusesReplacementOfAnOrdinarilyResavedProfileWithIdenticalText() throws IOException {
        var profile = Files.writeString(workspace.resolve("profile.toml"), "same");
        try (var files = open(); Closeable cleanup = files::discard) {
            var original = files.readOriginal();
            files.write("updated".getBytes(StandardCharsets.UTF_8));
            files.force();
            var resaved = Files.writeString(workspace.resolve("save.toml"), "same");
            Files.move(resaved, profile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            assertThatThrownBy(() -> files.verify(original)).isInstanceOf(IOException.class)
                .hasMessageContaining("changed before replacement");
        }
        assertThat(Files.readString(profile)).isEqualTo("same");
        assertThat(workspace.toFile().list()).containsExactlyInAnyOrder("profile.toml", ".profile.toml.lock");
    }

    @Test
    void writeAndSearchDirectorySupportsReplacementAndReportsSyncRefusal() throws IOException {
        assumeFalse(PosixPackDirectory.POSIX_API.geteuid() == 0);
        var profile = Files.writeString(workspace.resolve("profile.toml"), "original");
        var previous = Files.getPosixFilePermissions(workspace);
        Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("-wx------"));
        try (var files = open(); Closeable cleanup = files::discard) {
            var original = files.readOriginal();
            files.write("updated".getBytes(StandardCharsets.UTF_8));
            files.preserveMode(original.mode());
            files.force();
            files.verify(original);
            files.replace();
            assertThatThrownBy(files::syncDirectory).isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(workspace, previous);
        }
        assertThat(Files.readString(profile)).isEqualTo("updated");
        assertThat(workspace.toFile().list()).containsExactlyInAnyOrder("profile.toml", ".profile.toml.lock");
    }

    @Test
    void waitingForALiveWriterCanBeInterruptedAndRetried() throws Exception {
        Path profile = Files.writeString(workspace.resolve("profile.toml"), "original");
        var entered = new CountDownLatch(1);
        var outcome = new CompletableFuture<Boolean>();
        try (var held = open()) {
            var waiter = Thread.ofPlatform().start(() -> {
                entered.countDown();
                try (var second = open()) {
                    outcome.complete(false);
                } catch (InterruptedIOException interrupted) {
                    outcome.complete(Thread.currentThread().isInterrupted());
                } catch (IOException failure) {
                    outcome.completeExceptionally(failure);
                }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> outcome.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
                waiter.interrupt();
                assertThat(outcome.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                waiter.interrupt();
            }
        }
        try (var retry = open()) {
            assertThat(retry.readOriginal().text()).isEqualTo("original");
        }
        assertThat(Files.readString(profile)).isEqualTo("original");
    }

    private PosixProfileUpdate open() throws IOException {
        return new PosixProfileUpdate(workspace.toRealPath(), "profile.toml");
    }
}
