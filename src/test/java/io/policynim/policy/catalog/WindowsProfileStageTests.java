package io.policynim.policy.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsProfileStageTests {
    // non-English test data
    private static final String NAME = "notes-\u00e9-\ud83d\udcce.toml";

    @ParameterizedTest
    @ValueSource(ints = {4, 8})
    void laysOutARootedNoReplaceRenameForTheWindowsAbi(int pointerSize) {
        long handle = pointerSize == 8 ? 0x12345678abcdef01L : 0x89abcdefL;
        byte[] raw = WindowsProfileStage.renameInformation(pointerSize, handle, NAME, false);
        var info = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(info.getInt(0)).isZero();
        int rootOffset = pointerSize == 8 ? 8 : 4;
        long root = pointerSize == 8 ? info.getLong(rootOffset) : Integer.toUnsignedLong(info.getInt(rootOffset));
        assertThat(root).isEqualTo(handle);
        int lengthOffset = pointerSize == 8 ? 16 : 8;
        byte[] name = NAME.getBytes(StandardCharsets.UTF_16LE);
        assertThat(info.getInt(lengthOffset)).isEqualTo(name.length);
        int nameOffset = lengthOffset + Integer.BYTES;
        assertThat(Arrays.copyOfRange(raw, nameOffset, nameOffset + name.length)).isEqualTo(name);
        int structSize = pointerSize == 8 ? 24 : 16;
        assertThat(raw.length).isEqualTo(structSize + name.length);
        assertThat(info.getShort(nameOffset + name.length)).isZero();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void publishesTheUnicodeNameAndKeepsTheFirstFileOnCollision(@TempDir Path root) throws Exception {
        Path directory = root.toRealPath();
        byte[] first = {1, 2, 3};
        try (var selected = new WindowsProfileDirectory(directory)) {
            try (var stage = selected.stage()) {
                stage.write(first);
                stage.force();
                stage.verify();
                stage.publish(NAME);
                stage.verifyDirectory();
                stage.discard();
            }
            try (var second = selected.stage()) {
                second.write(new byte[]{4, 5});
                second.force();
                assertThatThrownBy(() -> second.publish(NAME)).isInstanceOf(FileAlreadyExistsException.class);
                second.discard();
            }
        }
        assertThat(Files.readAllBytes(directory.resolve(NAME))).isEqualTo(first);
        try (var contents = Files.list(directory)) {
            assertThat(contents.toList()).containsExactly(directory.resolve(NAME));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void checksResolvedDirectoryNamesAndAcceptsDriveAndShareRootCasing(@TempDir Path root) throws Exception {
        Path actual = Files.createDirectory(root.resolve("Review")).toRealPath();
        try (var selected = new WindowsProfileDirectory(actual)) {
            try (var stage = selected.stage()) {
                stage.verify();
                stage.discard();
            }
        }
        assertThatThrownBy(() -> new WindowsProfileDirectory(actual.resolveSibling("review")).close())
            .isInstanceOf(IOException.class);
        assertThat(WindowsPackDirectory.sameResolvedProfilePath(Path.of("C:\\Review"), Path.of("c:\\Review"))).isTrue();
        assertThat(WindowsPackDirectory.sameResolvedProfilePath(Path.of("\\\\Host\\Share\\Review"),
            Path.of("\\\\host\\share\\Review"))).isTrue();
        assertThat(WindowsPackDirectory.sameResolvedProfilePath(Path.of("\\\\Host\\Share\\Review"),
            Path.of("\\\\host\\share\\review"))).isFalse();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void opensTheSameNestedDirectoriesForCooperatingInitializers(@TempDir Path root) throws Exception {
        Path directory = root.toRealPath();
        Files.writeString(directory.resolve("notes"), "operator notes");
        try (var first = new WindowsProfileDirectory(directory);
             var second = new WindowsProfileDirectory(directory)) {
            assertThatThrownBy(() -> first.descend("notes")).isInstanceOf(IOException.class);
            first.descend("profiles");
            second.descend("profiles");
            second.descend("r\u00e9view");
            first.descend("r\u00e9view");
            try (var stage = first.stage()) {
                stage.write(new byte[]{1, 2});
                stage.force();
                stage.verify();
                stage.publish(NAME);
                stage.verifyDirectory();
            }
            try (var stage = second.stage()) {
                assertThatThrownBy(() -> stage.publish(NAME)).isInstanceOf(FileAlreadyExistsException.class);
                stage.discard();
            }
        }
        assertThat(Files.readString(directory.resolve("notes"))).isEqualTo("operator notes");
        Path nested = directory.resolve("profiles").resolve("r\u00e9view");
        assertThat(Files.readAllBytes(nested.resolve(NAME))).containsExactly(1, 2);
        try (var files = Files.list(nested)) {
            assertThat(files.toList()).containsExactly(nested.resolve(NAME));
        }
    }
}
