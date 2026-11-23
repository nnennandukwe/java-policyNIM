package io.policynim.policy.catalog;

import jnr.constants.platform.Errno;
import jnr.constants.platform.OpenFlags;
import jnr.posix.FileStat;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;

import static io.policynim.policy.catalog.PosixPackDirectory.POSIX_API;
import static io.policynim.policy.catalog.PosixProfileDirectory.NATIVE;
import static io.policynim.policy.catalog.PosixProfileDirectory.name;
import static io.policynim.policy.catalog.PosixProfileStage.closeFd;
import static io.policynim.policy.catalog.PosixProfileStage.flags;

/** Locks the same sibling inode as the Python CLI and atomically replaces the reviewed entry. */
final class PosixProfileUpdate implements ProfileUpdateFiles {
    // flock(2) uses LOCK_EX | LOCK_NB on both Darwin and Linux.
    private static final int EXCLUSIVE_NONBLOCKING = 2 | 4;
    private static final int READ = flags(OpenFlags.O_RDONLY, OpenFlags.O_NONBLOCK);
    private static final int LOCK = flags(OpenFlags.O_RDWR, OpenFlags.O_NONBLOCK);
    private record Identity(long device, long inode) { }

    private final PosixProfileDirectory directory;
    private final PosixPackDirectory parent;
    private final byte[] destination;
    private final PosixProfileStage stage;
    private int lock = -1;
    private int original = -1;

    PosixProfileUpdate(Path realParent, String fileName) throws IOException {
        destination = name(fileName);
        directory = new PosixProfileDirectory(realParent);
        try {
            parent = directory.checkedDirectory();
            stage = new PosixProfileStage(parent);
            byte[] lockName = name("." + fileName + ".lock");
            lock = openLock(lockName);
            statRegular(lock);
            acquire(lock);
            PosixProfileStage.verifyNamedFile(parent.descriptor(), lockName, READ, lock);
        } catch (IOException | RuntimeException | Error failure) {
            try (directory; Closeable release = () -> closeFd(lock)) {
                throw failure;
            }
        }
    }

    private int openLock(byte[] lockName) throws IOException {
        int create = LOCK | OpenFlags.O_CREAT.intValue() | OpenFlags.O_EXCL.intValue();
        int descriptor = NATIVE.openat(parent.descriptor(), lockName, create, 0600);
        if (descriptor < 0 && PosixProfileDirectory.errno() == Errno.EEXIST.intValue()) {
            return open(lockName, LOCK, 0);
        }
        return checked(descriptor);
    }

    private int open(byte[] entry, int flags, int mode) throws IOException {
        return checked(NATIVE.openat(parent.descriptor(), entry, flags, mode));
    }

    private static void acquire(int lock) throws IOException {
        for (;;) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Interrupted while acquiring the profile writer lock.");
            }
            if (POSIX_API.flock(lock, EXCLUSIVE_NONBLOCKING) == 0) {
                return;
            }
            int error = POSIX_API.errno();
            if (error == Errno.EINTR.intValue()) {
                continue;
            }
            if (error != Errno.EWOULDBLOCK.intValue() && error != Errno.EAGAIN.intValue()) {
                throw new IOException("Couldn't acquire the profile writer lock.");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for the profile writer lock.");
            }
        }
    }

    @Override
    public Original readOriginal() throws IOException {
        original = open(destination, READ, 0);
        return read(original);
    }

    private static FileStat statRegular(int file) throws IOException {
        var stat = POSIX_API.allocateStat();
        checked(POSIX_API.fstat(file, stat));
        if (!stat.isFile()) {
            throw new IOException("Choose a regular file for the profile and its writer lock.");
        }
        return stat;
    }

    private static Original read(int file) throws IOException {
        var stat = statRegular(file);
        return Original.from(PosixPackDirectory.readBytes(file), stat.mode() & 0777,
            new Identity(stat.dev(), stat.ino()));
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        stage.write(bytes);
    }

    @Override
    public void preserveMode(int mode) throws IOException {
        stage.preserveMode(mode);
    }

    @Override
    public void force() throws IOException {
        stage.force();
    }

    @Override
    public void verify(Original expected) throws IOException {
        stage.verify();
        int current = open(destination, READ, 0);
        try (Closeable release = () -> closeFd(current)) {
            if (!read(current).equals(expected)) {
                throw new IOException("The selected profile changed before replacement. Rerun profile diff.");
            }
        }
    }

    @Override
    public void replace() throws IOException {
        stage.replace(destination);
    }

    @Override
    public void syncDirectory() throws IOException {
        // Search handles support renameat, but fsync needs a readable descriptor for the same directory.
        int readable = open(name("."), flags(OpenFlags.O_RDONLY, OpenFlags.O_DIRECTORY), 0);
        try (Closeable release = () -> closeFd(readable)) {
            checked(POSIX_API.fsync(readable));
        }
    }

    @Override
    public void discard() throws IOException {
        stage.discard();
    }

    @Override
    public void close() throws IOException {
        try (directory; Closeable releaseLock = () -> closeFd(lock);
                Closeable releaseOriginal = () -> closeFd(original); stage) {
            // The sibling lock covers stage cleanup and closure. Its directory entry stays in place.
        }
    }

    private static int checked(int result) throws IOException {
        if (result < 0) {
            throw new IOException("Couldn't access the selected profile or its writer lock. Check file and directory access.");
        }
        return result;
    }
}
