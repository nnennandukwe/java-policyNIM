package io.policynim.policy.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        byte[] raw = WindowsProfileStage.renameInformation(pointerSize, handle, NAME);
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
        try (var stage = new WindowsProfileStage(directory)) {
            stage.write(first);
            stage.force();
            stage.verify();
            stage.publish(NAME);
            stage.verifyDirectory();
            stage.discard();
        }
        try (var second = new WindowsProfileStage(directory)) {
            second.write(new byte[]{4, 5});
            second.force();
            assertThatThrownBy(() -> second.publish(NAME)).isInstanceOf(FileAlreadyExistsException.class);
            second.discard();
        }
        assertThat(Files.readAllBytes(directory.resolve(NAME))).isEqualTo(first);
        try (var contents = Files.list(directory)) {
            assertThat(contents.toList()).containsExactly(directory.resolve(NAME));
        }
    }
}
