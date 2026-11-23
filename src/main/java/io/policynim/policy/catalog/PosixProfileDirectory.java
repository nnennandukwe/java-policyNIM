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
        @SaveError int unlinkat(int parent, @In byte[] name, int flags);
    }

    static final DirectoryCalls NATIVE = LibraryLoader.create(DirectoryCalls.class)
        .load(Platform.getNativePlatform().getStandardCLibraryName());
    private static final int OPEN_DIRECTORY = OpenFlags.O_DIRECTORY.intValue() | OpenFlags.O_RDONLY.intValue()
        | OpenFlags.O_NOFOLLOW.intValue() | OpenFlags.O_CLOEXEC.intValue();
    private PosixPackDirectory current;

    PosixProfileDirectory(Path existingRealDirectory) throws IOException {
        current = new PosixPackDirectory(existingRealDirectory);
    }

    @Override
    public void descend(String component) throws IOException {
        if (component.isEmpty() || component.equals(".") || component.equals("..")
                || component.indexOf('/') >= 0 || component.indexOf('\0') >= 0) {
            throw new IOException("Choose a directory name inside the selected profile destination.");
        }
        var parent = checkedDirectory();
        Path child = parent.root.resolve(component);
        byte[] leaf = name(component);
        int fd = NATIVE.openat(parent.descriptor(), leaf, OPEN_DIRECTORY, 0);
        if (fd < 0 && errno() == Errno.ENOENT.intValue()) {
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

    private PosixPackDirectory checkedDirectory() throws IOException {
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
