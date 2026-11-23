package io.policynim.policy.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({OS.LINUX, OS.MAC})
class PosixProfileDirectoryTests {
    @TempDir Path workspace;

    @Test
    void concurrentWritersReuseOrdinaryDirectoriesAndCleanTheirOwnStages() throws Exception {
        Path anchor = workspace.toRealPath();
        var ready = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(writer(anchor, "first", ready));
            var b = pool.submit(writer(anchor, "second", ready));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
        Path destination = anchor.resolve("profiles/team");
        assertThat(destination.toFile().list()).containsExactlyInAnyOrder("first", "second");
        assertThat(Files.readString(destination.resolve("first"))).isEqualTo("first");
        assertThat(Files.readString(destination.resolve("second"))).isEqualTo("second");
    }

    private Callable<Void> writer(Path anchor, String name, CyclicBarrier ready) {
        return () -> {
            try (var directory = new PosixProfileDirectory(anchor)) {
                ready.await(5, TimeUnit.SECONDS);
                directory.descend("profiles");
                directory.descend("team");
                try (var stage = directory.stage()) {
                    try {
                        stage.write(name.getBytes(UTF_8));
                        stage.force();
                        stage.verify();
                        stage.publish(name);
                    } finally {
                        stage.discard();
                    }
                }
            }
            return null;
        };
    }

    @Test
    void refusesADirectoryRelocatedBeforeDescent() throws IOException {
        Path anchor = Files.createDirectory(workspace.resolve("selected")).toRealPath();
        Path moved = workspace.resolve("moved");
        try (var directory = new PosixProfileDirectory(anchor)) {
            Files.move(anchor, moved);
            assertThatThrownBy(() -> directory.descend("profiles")).isInstanceOf(IOException.class);
        }
        assertThat(moved.toFile().list()).isEmpty();
        assertThat(anchor).doesNotExist();
    }

    @Test
    void closingAStageLeavesItsDirectoryOpenForTheNextStage() throws IOException {
        var directory = new PosixProfileDirectory(workspace.toRealPath());
        try (directory) {
            try (var stage = directory.stage()) {
                stage.write("Partial.".getBytes(UTF_8));
                stage.discard();
            }
            try (var stage = directory.stage()) {
                stage.write("Reviewed.".getBytes(UTF_8));
                stage.force();
                stage.verify();
                stage.publish("team.toml");
                stage.discard();
            }
        }
        assertThat(workspace.toFile().list()).containsExactly("team.toml");
        assertThat(Files.readString(workspace.resolve("team.toml"))).isEqualTo("Reviewed.");
        assertThatThrownBy(directory::stage).isInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(() -> directory.descend("next")).isInstanceOf(ClosedChannelException.class);
    }

    @Test
    void rejectsAnOccupiedComponentAndCanRetryAfterItIsCleared() throws IOException {
        var occupied = Files.writeString(workspace.resolve("profiles"), "Contents");
        try (var directory = new PosixProfileDirectory(workspace.toRealPath())) {
            assertThatThrownBy(() -> directory.descend("profiles")).isInstanceOf(IOException.class);
            assertThat(Files.readString(occupied)).isEqualTo("Contents");
            Files.delete(occupied);
            directory.descend("profiles");
            try (var stage = directory.stage()) {
                stage.write("Reviewed.".getBytes(UTF_8));
                stage.discard();
            }
        }
        assertThat(occupied.toFile().list()).isEmpty();
    }
}
