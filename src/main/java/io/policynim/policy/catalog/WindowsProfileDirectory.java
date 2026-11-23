package io.policynim.policy.catalog;

import jnr.ffi.LibraryLoader;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.annotations.In;
import jnr.ffi.annotations.Out;
import jnr.ffi.byref.PointerByReference;

import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Holds the selected Windows directory and opens each child relative to the current handle. */
final class WindowsProfileDirectory implements ProfileDirectory {
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
    private WindowsPackDirectory current;

    WindowsProfileDirectory(Path existingRealDirectory) throws IOException {
        current = WindowsPackDirectory.openProfileRoot(existingRealDirectory, FILE_TRAVERSE);
    }

    private WindowsPackDirectory selected() throws IOException {
        if (current == null) {
            throw new ClosedChannelException();
        }
        current.verifyPublicationRoot();
        return current;
    }

    @Override
    public void descend(String component) throws IOException {
        requireLeaf(component);
        var parent = selected();
        Path expected = parent.root.resolve(component);
        final int shareReadWriteDelete = 7, fileOpenIf = 3, directoryFile = 1;
        var child = openRelative(parent.rootHandle, component, FILE_TRAVERSE, shareReadWriteDelete,
            fileOpenIf, directoryFile, "open or create the profile directory");
        current = new WindowsPackDirectory(expected, child);
        parent.close();
    }

    @Override
    public ProfileStage stage() throws IOException {
        return new WindowsProfileStage(selected());
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            var selected = current;
            current = null;
            selected.close();
        }
    }

    static void requireLeaf(String name) throws IOException {
        Path leaf = Path.of(name);
        if (leaf.getRoot() != null || leaf.getNameCount() != 1 || !leaf.toString().equals(name)
            || name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 32767) {
            throw new IOException("Choose a file name within the profile directory.");
        }
    }

    static Pointer createStageFile(Pointer parent, String name) throws IOException {
        final int delete = 0x10000, genericWrite = 0x40000000, shareReadDelete = 5, fileCreate = 2, nonDirectory = 0x40;
        return openRelative(parent, name, genericWrite | delete, shareReadDelete, fileCreate, nonDirectory,
            "create the profile staging file");
    }

    private static Pointer openRelative(Pointer parent, String name, int access, int sharing, int disposition,
                                        int fileType, String operation) throws IOException {
        final int synchronize = 0x100000, readAttributes = 0x80, fileAttributeNormal = 0x80;
        final int synchronousNonalert = 0x20, openReparsePoint = 0x200000;
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
        int status = Native.API.NtCreateFile(created, access | synchronize | readAttributes,
            attributes, ioStatusBlock(), null, fileAttributeNormal, sharing, disposition,
            synchronousNonalert | openReparsePoint | fileType, null, 0);
        Reference.reachabilityFence(unicode);
        Reference.reachabilityFence(text);
        check(status, operation);
        return created.getValue();
    }

    // IO_STATUS_BLOCK has a status/pointer union followed by a pointer-sized information count.
    private static Pointer ioStatusBlock() {
        return Memory.allocateDirect(Native.RUNTIME, 2 * Native.RUNTIME.addressSize(), true);
    }

    static void check(int status, String operation) throws IOException {
        if (status != 0) {
            throw new IOException("Couldn't " + operation + " (NTSTATUS 0x" + Integer.toHexString(status) + ").");
        }
    }

    static int setInformation(Pointer handle, int infoClass, byte[] data) {
        return Native.API.NtSetInformationFile(handle, ioStatusBlock(), data, data.length, infoClass);
    }
}
