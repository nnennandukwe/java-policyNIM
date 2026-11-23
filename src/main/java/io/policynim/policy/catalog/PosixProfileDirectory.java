package io.policynim.policy.catalog;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import jnr.constants.platform.Errno;
import jnr.constants.platform.OpenFlags;
import jnr.ffi.LibraryLoader;
import jnr.ffi.Platform;
import jnr.ffi.annotations.In;
import jnr.ffi.annotations.SaveError;
import jnr.ffi.annotations.Variadic;

/** Keeps the selected output directory open while discovering or creating its children. */
final class PosixProfileDirectory implements ProfileDirectory {
    public interface DirectoryCalls {
        @SaveError int mkdirat(int parent, @In byte[] name, int mode);
        @SaveError @Variadic(fixedCount = 3) int openat(int parent, @In byte[] name, int flags, int mode);
        @SaveError int linkat(int source, @In byte[] name, int destination, @In byte[] output, int flags);
        @SaveError int renameat(int source, @In byte[] name, int destination, @In byte[] output);
        @SaveError int unlinkat(int parent, @In byte[] name, int flags);
    }

    static final DirectoryCalls NATIVE = LibraryLoader.create(DirectoryCalls.class)
        .load(Platform.getNativePlatform().getStandardCLibraryName());
    // JNR's OpenFlags omits these search modes from XNU sys/fcntl.h and Linux UAPI asm/fcntl.h.
    private static final int DARWIN_O_EXEC = 0x40000000;
    private static final int LINUX_O_PATH = 010000000;
    private static final int LINUX_SPARC_O_PATH = 0x1000000;
    private static final int OPEN_DIRECTORY = OpenFlags.O_DIRECTORY.intValue() | searchMode()
        | OpenFlags.O_NOFOLLOW.intValue() | OpenFlags.O_CLOEXEC.intValue();
    private PosixPackDirectory current;

    PosixProfileDirectory(Path existingRealDirectory) throws IOException {
        if (!existingRealDirectory.isAbsolute() || !existingRealDirectory.equals(existingRealDirectory.normalize())) {
            throw new IOException("Choose a resolved absolute profile directory.");
        }
        current = PosixPackDirectory.openFileSystemRoot(OPEN_DIRECTORY);
        try {
            for (Path component : existingRealDirectory) {
                openChild(component, false);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try (var owned = current) {
                throw failure;
            }
        }
    }

    private static int searchMode() {
        var platform = Platform.getNativePlatform();
        return switch (platform.getOS()) {
            case DARWIN -> DARWIN_O_EXEC; // O_SEARCH is O_EXEC | O_DIRECTORY.
            case LINUX -> switch (platform.getCPU()) {
                case SPARC, SPARCV9 -> LINUX_SPARC_O_PATH;
                case UNKNOWN -> OpenFlags.O_RDONLY.intValue();
                default -> LINUX_O_PATH;
            };
            default -> OpenFlags.O_RDONLY.intValue();
        };
    }

    @Override
    public void descend(String component) throws IOException {
        if (component.isEmpty() || component.equals(".") || component.equals("..")
                || component.indexOf('/') >= 0 || component.indexOf('\0') >= 0) {
            throw new IOException("Choose a directory name inside the selected profile destination.");
        }
        openChild(Path.of(component), true);
    }

    private void openChild(Path component, boolean allowCreation) throws IOException {
        var parent = checkedDirectory();
        Path child = parent.root.resolve(component);
        byte[] leaf = PosixPackDirectory.nativeFileName(child);
        int fd = NATIVE.openat(parent.descriptor(), leaf, OPEN_DIRECTORY, 0);
        if (fd < 0 && allowCreation && errno() == Errno.ENOENT.intValue()) {
            // Use normal directory permissions subject to the caller's umask.
            if (NATIVE.mkdirat(parent.descriptor(), leaf, 0777) < 0 && errno() != Errno.EEXIST.intValue()) {
                throw new IOException("Couldn't create the selected profile directory. Check directory access and retry.");
            }
            fd = NATIVE.openat(parent.descriptor(), leaf, OPEN_DIRECTORY, 0);
        }
        if (fd < 0) {
            throw new IOException("Couldn't open the selected profile directory. Check directory access and retry.");
        }
        current = new PosixPackDirectory(child, fd);
        parent.close();
    }

    @Override
    public ProfileStage stage() throws IOException {
        return new PosixProfileStage(checkedDirectory());
    }

    PosixPackDirectory checkedDirectory() throws IOException {
        if (current == null) {
            throw new ClosedChannelException();
        }
        current.verifyRoot();
        return current;
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            var owned = current;
            current = null;
            owned.close();
        }
    }

    static byte[] name(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(bytes, bytes.length + 1);
    }

    static int errno() {
        return jnr.ffi.Runtime.getRuntime(NATIVE).getLastError();
    }
}
