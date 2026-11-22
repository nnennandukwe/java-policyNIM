package io.policynim.policy.catalog;

import jnr.ffi.LibraryLoader;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.annotations.In;
import jnr.ffi.annotations.Out;
import jnr.ffi.byref.IntByReference;
import jnr.ffi.byref.PointerByReference;

import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.UUID;

/** Creates and renames a staging file under a held Windows directory handle. */
final class WindowsProfileStage extends ProfileStage {
    public interface NtDll {
        int NtCreateFile(@Out PointerByReference handle, int access, Pointer objectAttributes, Pointer ioStatusBlock,
                         Pointer allocationSize, int attributes, int sharing, int disposition, int options,
                         Pointer extendedAttributes, int extendedAttributesSize);
        int NtSetInformationFile(Pointer handle, Pointer ioStatusBlock, @In byte[] information, int size, int infoClass);
    }

    private static final class Native {
        static final NtDll API = LibraryLoader.create(NtDll.class).stdcall().load("ntdll");
        static final jnr.ffi.Runtime RUNTIME = jnr.ffi.Runtime.getRuntime(API);
    }

    private static final int FILE_TRAVERSE = 0x20;
    private static final int FILE_RENAME_INFORMATION = 10;
    private static final int FILE_DISPOSITION_INFORMATION = 13;
    private static final int STATUS_OBJECT_NAME_COLLISION = 0xC0000035;
    private final WindowsPackDirectory directory;
    private final Path stagePath;
    private Pointer file;
    private boolean staged = true;

    WindowsProfileStage(Path realParent) throws IOException {
        String name = ".policynim-profile-" + UUID.randomUUID() + ".tmp";
        stagePath = realParent.resolve(name);
        directory = new WindowsPackDirectory(realParent, FILE_TRAVERSE);
        try {
            file = createRelativeFile(directory.rootHandle, name);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                directory.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static Pointer createRelativeFile(Pointer parent, String name) throws IOException {
        final int delete = 0x10000, synchronize = 0x100000, genericWrite = 0x40000000, readAttributes = 0x80;
        final int shareReadDelete = 5, fileCreate = 2, fileAttributeNormal = 0x80;
        final int synchronousNonalert = 0x20, nonDirectory = 0x40, openReparsePoint = 0x200000;
        var runtime = Native.RUNTIME;
        int pointerSize = runtime.addressSize();
        byte[] bytes = name.getBytes(StandardCharsets.UTF_16LE);
        Pointer text = Memory.allocateDirect(runtime, bytes.length);
        text.put(0, bytes, 0, bytes.length);
        // UNICODE_STRING stores two byte lengths, then a naturally aligned PWSTR.
        Pointer unicode = Memory.allocateDirect(runtime, 2 * pointerSize, true);
        unicode.putShort(0, (short) bytes.length);
        unicode.putShort(2, (short) bytes.length);
        unicode.putPointer(pointerSize, text);
        // OBJECT_ATTRIBUTES starts with Length, RootDirectory, ObjectName and Attributes.
        int size = 6 * pointerSize;
        Pointer attributes = Memory.allocateDirect(runtime, size, true);
        attributes.putInt(0, size);
        attributes.putPointer(pointerSize, parent);
        attributes.putPointer(2L * pointerSize, unicode);
        attributes.putInt(3L * pointerSize, 0x40); // OBJ_CASE_INSENSITIVE
        var created = new PointerByReference();
        int status = Native.API.NtCreateFile(created, genericWrite | delete | synchronize | readAttributes,
            attributes, ioStatusBlock(), null, fileAttributeNormal, shareReadDelete, fileCreate,
            synchronousNonalert | nonDirectory | openReparsePoint, null, 0);
        Reference.reachabilityFence(unicode);
        Reference.reachabilityFence(text);
        check(status, "create the profile staging file");
        return created.getValue();
    }

    // IO_STATUS_BLOCK has a status/pointer union followed by a pointer-sized information count.
    private static Pointer ioStatusBlock() {
        return Memory.allocateDirect(Native.RUNTIME, 2 * Native.RUNTIME.addressSize(), true);
    }

    private static void check(int status, String operation) throws IOException {
        if (status != 0) {
            throw new IOException("Couldn't " + operation + " (NTSTATUS 0x" + Integer.toHexString(status) + ").");
        }
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
        Pointer data = Memory.allocateDirect(Native.RUNTIME, bytes.length);
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
        directory.verifyRoot();
        checkIdentity(directory.rootHandle, directory.root);
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
        Path leaf = Path.of(outputName);
        if (leaf.getRoot() != null || leaf.getNameCount() != 1 || !leaf.toString().equals(outputName)
            || outputName.isEmpty() || outputName.equals(".") || outputName.equals("..")) {
            throw new IOException("Choose a file name within the profile directory.");
        }
        byte[] information = renameInformation(Native.RUNTIME.addressSize(), directory.rootHandle.address(), outputName);
        int status = Native.API.NtSetInformationFile(stagedHandle(), ioStatusBlock(), information,
            information.length, FILE_RENAME_INFORMATION);
        if (status == STATUS_OBJECT_NAME_COLLISION) {
            throw new FileAlreadyExistsException(outputName);
        }
        check(status, "publish the new profile");
        staged = false;
    }

    static byte[] renameInformation(int pointerSize, long targetDirectory, String name) {
        // FILE_RENAME_INFORMATION has a zero replacement flag, aligned HANDLE, ULONG byte count and WCHAR array.
        int nameOffset = 2 * pointerSize + Integer.BYTES;
        int nameBytes = name.length() * Character.BYTES;
        // Windows requires sizeof(FILE_RENAME_INFORMATION), including trailing padding, plus the name bytes.
        int structSize = pointerSize == Long.BYTES ? 24 : 16;
        var info = ByteBuffer.allocate(structSize + nameBytes).order(ByteOrder.LITTLE_ENDIAN);
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
            byte[] delete = {1};
            int status = Native.API.NtSetInformationFile(stagedHandle(), ioStatusBlock(), delete,
                delete.length, FILE_DISPOSITION_INFORMATION);
            check(status, "discard the profile staging file");
            staged = false;
        }
    }

    @Override
    public void close() throws IOException {
        if (file != null) {
            Pointer opened = file;
            file = null;
            try (directory) {
                WindowsPackDirectory.closeHandle(opened);
            }
        }
    }
}
