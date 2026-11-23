package io.policynim.policy.catalog;

import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.byref.IntByReference;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.UUID;

/** Creates and renames a staging file under a held Windows directory handle. */
final class WindowsProfileStage extends ProfileStage {
    private static final int FILE_RENAME_INFORMATION = 10;
    private static final int FILE_DISPOSITION_INFORMATION = 13;
    private static final int STATUS_OBJECT_NAME_COLLISION = 0xC0000035;
    private final WindowsPackDirectory directory;
    private final Path stagePath;
    private Pointer file;
    private boolean staged = true;

    WindowsProfileStage(WindowsPackDirectory directory) throws IOException {
        String name = ".policynim-profile-" + UUID.randomUUID() + ".tmp";
        this.directory = directory;
        stagePath = directory.root.resolve(name);
        file = WindowsProfileDirectory.createStageFile(directory.rootHandle, name);
    }

    private Pointer stagedHandle() throws IOException {
        if (file == null || !staged) {
            throw new ClosedChannelException();
        }
        return file;
    }

    @Override
    void write(byte[] bytes) throws IOException {
        Pointer handle = stagedHandle();
        if (bytes.length == 0) {
            return;
        }
        Pointer data = Memory.allocateDirect(handle.getRuntime(), bytes.length);
        data.put(0, bytes, 0, bytes.length);
        var written = new IntByReference();
        for (int offset = 0; offset < bytes.length;) {
            int remaining = bytes.length - offset;
            if (WindowsPackDirectory.API.WriteFile(handle, data.slice(offset), remaining, written, null) == 0
                || written.intValue() <= 0 || written.intValue() > remaining) {
                throw new IOException("Couldn't write the profile staging file.");
            }
            offset += written.intValue();
        }
    }

    @Override
    void force() throws IOException {
        if (WindowsPackDirectory.API.FlushFileBuffers(stagedHandle()) == 0) {
            throw new IOException("Couldn't flush the profile staging file.");
        }
    }

    @Override
    void verifyDirectory() throws IOException {
        if (file == null) {
            throw new ClosedChannelException();
        }
        directory.verifyPublicationRoot();
    }

    @Override
    void verify() throws IOException {
        verifyDirectory();
        checkIdentity(stagedHandle(), stagePath);
    }

    private static void checkIdentity(Pointer handle, Path path) throws IOException {
        if (!WindowsPackDirectory.fileId(handle).equals(WindowsPackDirectory.publicationFileId(path))) {
            throw new IOException("The profile staging file or its directory changed.");
        }
    }

    @Override
    void publish(String outputName) throws IOException {
        rename(directory, outputName, false);
    }

    void replace(WindowsPackDirectory destination, String outputName) throws IOException {
        rename(destination, outputName, true);
    }

    private void rename(WindowsPackDirectory destination, String outputName, boolean replace) throws IOException {
        WindowsProfileDirectory.requireLeaf(outputName);
        Pointer handle = stagedHandle();
        byte[] information = renameInformation(handle.getRuntime().addressSize(), destination.rootHandle.address(), outputName, replace);
        int status = WindowsProfileDirectory.setInformation(handle, FILE_RENAME_INFORMATION, information);
        if (status == STATUS_OBJECT_NAME_COLLISION) {
            throw new FileAlreadyExistsException(outputName);
        }
        WindowsProfileDirectory.check(status, "publish the staged profile");
        staged = false;
    }

    static byte[] renameInformation(int pointerSize, long targetDirectory, String name, boolean replace) {
        // FILE_RENAME_INFORMATION has a replacement flag, aligned HANDLE, ULONG byte count and WCHAR array.
        int nameOffset = 2 * pointerSize + Integer.BYTES;
        int nameBytes = name.length() * Character.BYTES;
        // Windows requires sizeof(FILE_RENAME_INFORMATION), including trailing padding, plus the name bytes.
        int structSize = pointerSize == Long.BYTES ? 24 : 16;
        var info = ByteBuffer.allocate(structSize + nameBytes).order(ByteOrder.LITTLE_ENDIAN);
        info.put(0, (byte) (replace ? 1 : 0));
        if (pointerSize == Long.BYTES) {
            info.putLong(pointerSize, targetDirectory);
        } else {
            info.putInt(pointerSize, (int) targetDirectory);
        }
        info.putInt(2 * pointerSize, nameBytes);
        info.position(nameOffset);
        info.asCharBuffer().put(name);
        return info.array();
    }

    @Override
    void discard() throws IOException {
        if (staged) {
            deleteOnClose(stagedHandle());
            staged = false;
        }
    }

    static void deleteOnClose(Pointer handle) throws IOException {
        int status = WindowsProfileDirectory.setInformation(handle, FILE_DISPOSITION_INFORMATION, new byte[]{1});
        WindowsProfileDirectory.check(status, "discard the profile staging entry");
    }

    @Override
    public void close() throws IOException {
        if (file != null) {
            Pointer opened = file;
            file = null;
            WindowsPackDirectory.closeHandle(opened);
        }
    }
}
