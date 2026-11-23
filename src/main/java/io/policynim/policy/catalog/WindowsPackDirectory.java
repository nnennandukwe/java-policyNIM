package io.policynim.policy.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import jnr.ffi.LibraryLoader;
import jnr.ffi.Pointer;
import jnr.ffi.Struct;
import jnr.ffi.annotations.In;
import jnr.ffi.annotations.Out;
import jnr.ffi.byref.IntByReference;
import jnr.posix.windows.WindowsByHandleFileInformation;

final class WindowsPackDirectory extends PackDirectory {

    public interface Kernel32 {
        Pointer CreateFileW(@In byte[] path, int access, int sharing, Pointer security, int disposition,
                            int attributes, Pointer template);
        int GetFileType(Pointer handle);
        int GetFileInformationByHandle(Pointer handle, Pointer information);
        int GetFileInformationByHandleEx(Pointer handle, int infoClass, @Out byte[] information, int size);
        int GetFinalPathNameByHandleW(Pointer handle, @Out byte[] path, int characters, int flags);
        int ReadFile(Pointer handle, @Out byte[] bytes, int length, @Out IntByReference count, Pointer overlapped);
        int WriteFile(Pointer handle, Pointer bytes, int length, @Out IntByReference count, Pointer overlapped);
        int FlushFileBuffers(Pointer handle);
        int CloseHandle(Pointer handle);
    }

    static final Kernel32 API = LibraryLoader.create(Kernel32.class).stdcall().load("kernel32");
    private static final int GENERIC_READ = 0x80000000;
    private static final int FILE_READ_ATTRIBUTES = 0x80;
    private static final int SHARE_READ_WRITE_DELETE = 7;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x80;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_TYPE_DISK = 1;
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
    final Pointer rootHandle;

    WindowsPackDirectory(Path root) throws IOException {
        this(root, 0);
    }

    WindowsPackDirectory(Path root, int additionalAccess) throws IOException {
        this(root, openHandle(root, FILE_READ_ATTRIBUTES | additionalAccess,
            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT));
    }

    /** Takes ownership of an opened directory handle, including failure to validate it. */
    WindowsPackDirectory(Path root, Pointer opened) throws IOException {
        super(root);
        try {
            int attributes = attributes(opened);
            if ((attributes & FILE_ATTRIBUTE_DIRECTORY) == 0 || (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0
                    || !finalPath(opened).equals(root)) {
                throw new ReaderFailure(Failure.ROOT_CHANGED, null);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                closeHandle(opened);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        rootHandle = opened;
    }

    void verifyPublicationRoot() throws IOException {
        verifyRoot();
        if (!fileId(rootHandle).equals(publicationFileId(root))) {
            throw new IOException("The profile directory changed.");
        }
    }

    private static Pointer openHandle(Path path, int access, int attributes) throws IOException {
        String name = path.toString();
        String full = name.startsWith("\\\\") ? "\\\\?\\UNC\\" + name.substring(2) : "\\\\?\\" + name;
        byte[] wide = (full + '\0').getBytes(StandardCharsets.UTF_16LE);
        Pointer handle = API.CreateFileW(wide, access, SHARE_READ_WRITE_DELETE, null, OPEN_EXISTING, attributes, null);
        if (handle == null || handle.address() == -1L) {
            throw new IOException("Couldn't open the selected pack file.");
        }
        return handle;
    }

    private static Path finalPath(Pointer handle) throws IOException {
        int capacity = 32768;
        byte[] name = new byte[capacity * 2];
        int count = API.GetFinalPathNameByHandleW(handle, name, capacity, 0);
        if (count <= 0 || count >= capacity) {
            throw new IOException("Couldn't validate the selected pack path.");
        }
        String wide = new String(name, 0, count * 2, StandardCharsets.UTF_16LE);
        if (wide.startsWith("\\\\?\\UNC\\")) {
            return Path.of("\\\\" + wide.substring(8));
        }
        if (wide.startsWith("\\\\?\\")) {
            return Path.of(wide.substring(4));
        }
        return Path.of(wide);
    }

    // FILE_ID_INFO contains a 64-bit volume serial followed by a 128-bit file identifier.
    private record FileId(long volume, long first, long second) { }

    static Object publicationFileId(Path path) throws IOException {
        Pointer handle = openHandle(path, FILE_READ_ATTRIBUTES, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT);
        try {
            return fileId(handle);
        } finally {
            closeHandle(handle);
        }
    }

    static Object fileId(Pointer handle) throws IOException {
        final int fileIdInfo = 18;
        byte[] data = new byte[24];
        if (API.GetFileInformationByHandleEx(handle, fileIdInfo, data, data.length) == 0) {
            throw new IOException("Couldn't establish file identity for profile publication.");
        }
        var id = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new FileId(id.getLong(), id.getLong(), id.getLong());
    }

    private static int attributes(Pointer handle) throws IOException {
        var info = new WindowsByHandleFileInformation(jnr.ffi.Runtime.getRuntime(API));
        if (API.GetFileType(handle) != FILE_TYPE_DISK || API.GetFileInformationByHandle(handle, Struct.getMemory(info)) == 0) {
            throw new IOException("Couldn't verify the selected pack file type.");
        }
        return info.getFileAttributes();
    }

    @Override
    void verifyRoot() throws IOException {
        if (!root.equals(finalPath(rootHandle))) {
            throw new ReaderFailure(Failure.ROOT_CHANGED, null);
        }
    }

    @Override
    byte[] readRegular(Path physical) throws IOException {
        Pointer handle = openHandle(physical, GENERIC_READ, FILE_ATTRIBUTE_NORMAL);
        try {
            Path opened = finalPath(handle);
            requireMember(opened);
            verifyRoot();
            if (!opened.equals(physical)
                    || (attributes(handle) & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                throw new IOException("Couldn't validate the selected regular pack file.");
            }
            var result = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            var count = new IntByReference();
            for (;;) {
                if (API.ReadFile(handle, buffer, buffer.length, count, null) == 0) {
                    throw new IOException("Couldn't read the selected pack file.");
                }
                int length = count.intValue();
                if (length == 0) {
                    return result.toByteArray();
                }
                result.write(buffer, 0, length);
            }
        } finally {
            closeHandle(handle);
        }
    }

    static void closeHandle(Pointer handle) throws IOException {
        if (API.CloseHandle(handle) == 0) {
            throw new IOException("Couldn't close the selected pack file.");
        }
    }

    @Override
    void closeRoot() throws IOException {
        closeHandle(rootHandle);
    }
}
