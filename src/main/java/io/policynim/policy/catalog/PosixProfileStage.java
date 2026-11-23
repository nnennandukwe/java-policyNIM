package io.policynim.policy.catalog;

import jnr.constants.platform.Errno;
import jnr.constants.platform.OpenFlags;
import jnr.ffi.Platform;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.util.UUID;

import static io.policynim.policy.catalog.PosixPackDirectory.POSIX_API;
import static io.policynim.policy.catalog.PosixProfileDirectory.NATIVE;
import static io.policynim.policy.catalog.PosixProfileDirectory.errno;
import static io.policynim.policy.catalog.PosixProfileDirectory.name;

/** Stages private bytes and resolves publication and cleanup names against held directory descriptors. */
final class PosixProfileStage extends ProfileStage {
    private static final int DIRECTORY = flags(OpenFlags.O_RDONLY, OpenFlags.O_DIRECTORY);
    private static final int REGULAR = flags(OpenFlags.O_RDONLY, OpenFlags.O_NONBLOCK);
    private static final int CREATE = flags(OpenFlags.O_CREAT, OpenFlags.O_EXCL, OpenFlags.O_RDWR);
    private static final int AT_REMOVEDIR = Platform.getNativePlatform().getOS() == Platform.OS.DARWIN ? 0x80 : 0x200;
    private static final byte[] BODY = name("profile");

    private final PosixPackDirectory parent;
    private final byte[] stageName = name(".policynim-profile-" + UUID.randomUUID() + ".tmp");
    private boolean directoryCreated;
    private boolean bodyMoved;
    private int directory = -1;
    private int file = -1;

    PosixProfileStage(PosixPackDirectory parent) {
        this.parent = parent;
    }

    @Override
    void write(byte[] bytes) throws IOException {
        require(NATIVE.mkdirat(parent.descriptor(), stageName, 0700));
        directoryCreated = true;
        directory = require(NATIVE.openat(parent.descriptor(), stageName, DIRECTORY, 0));
        verifyPrivateDirectory();
        file = require(NATIVE.openat(directory, BODY, CREATE, 0600));
        ByteBuffer input = ByteBuffer.wrap(bytes);
        while (input.hasRemaining()) {
            int count = POSIX_API.write(file, input.slice(), input.remaining());
            if (count < 0 && POSIX_API.errno() == Errno.EINTR.intValue()) {
                continue;
            }
            if (count <= 0) {
                throw new IOException("Couldn't write the complete profile staging file.");
            }
            input.position(input.position() + count);
        }
    }

    @Override
    void force() throws IOException {
        require(POSIX_API.fsync(file));
    }

    @Override
    void verifyDirectory() throws IOException {
        parent.verifyRoot();
    }

    @Override
    void verify() throws IOException {
        verifyDirectory();
        verifyNamedFile(parent.descriptor(), stageName, DIRECTORY, directory);
        verifyNamedFile(directory, BODY, REGULAR, file);
    }

    @Override
    void publish(String outputName) throws IOException {
        byte[] output = name(outputName);
        // Both directory arguments refer to open descriptors. The source name is inside our mode-0700 stage.
        int result = NATIVE.linkat(directory, BODY, parent.descriptor(), output, 0);
        if (result < 0 && errno() == Errno.EEXIST.intValue()) {
            throw new FileAlreadyExistsException(outputName);
        }
        require(result);
    }

    void preserveMode(int mode) throws IOException {
        require(POSIX_API.fchmod(file, mode));
    }

    void replace(byte[] outputName) throws IOException {
        require(NATIVE.renameat(directory, BODY, parent.descriptor(), outputName));
        bodyMoved = true;
    }

    @Override
    void discard() throws IOException {
        if (!directoryCreated) {
            return;
        }
        if (directory < 0) {
            throw new IOException("Couldn't verify the profile staging directory. Inspect the output directory before retrying.");
        }
        verifyPrivateDirectory();
        if (file >= 0 && !bodyMoved) {
            verifyNamedFile(directory, BODY, REGULAR, file);
            require(NATIVE.unlinkat(directory, BODY, 0));
        }
        verifyNamedFile(parent.descriptor(), stageName, DIRECTORY, directory);
        require(NATIVE.unlinkat(parent.descriptor(), stageName, AT_REMOVEDIR));
    }

    private void verifyPrivateDirectory() throws IOException {
        var stat = POSIX_API.allocateStat();
        require(POSIX_API.fstat(directory, stat));
        if (!stat.isDirectory() || stat.uid() != POSIX_API.geteuid() || (stat.mode() & 0077) != 0) {
            throw new IOException("Couldn't verify ownership and private permissions for the profile staging directory.");
        }
    }

    static void verifyNamedFile(int parent, byte[] name, int flags, int expected) throws IOException {
        int current = require(NATIVE.openat(parent, name, flags, 0));
        try (Closeable ignored = () -> closeFd(current)) {
            var before = POSIX_API.allocateStat();
            var after = POSIX_API.allocateStat();
            require(POSIX_API.fstat(expected, before));
            require(POSIX_API.fstat(current, after));
            if (before.dev() != after.dev() || before.ino() != after.ino()) {
                throw new IOException("The profile staging entry changed.");
            }
        }
    }

    @Override
    public void close() throws IOException {
        try (Closeable closeDirectory = () -> closeFd(directory)) {
            closeFd(file);
        }
    }

    static void closeFd(int fd) throws IOException {
        if (fd >= 0) {
            require(POSIX_API.close(fd));
        }
    }

    private static int require(int result) throws IOException {
        if (result < 0) {
            throw new IOException("Couldn't access the selected profile staging area. Check directory access and retry.");
        }
        return result;
    }

    static int flags(OpenFlags... values) {
        int result = OpenFlags.O_CLOEXEC.intValue() | OpenFlags.O_NOFOLLOW.intValue();
        for (var value : values) {
            result |= value.intValue();
        }
        return result;
    }
}
