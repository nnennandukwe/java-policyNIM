package io.policynim.policy.catalog;

import jnr.ffi.LibraryLoader;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.annotations.In;
import jnr.ffi.annotations.Out;
import jnr.ffi.byref.PointerByReference;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/** Owns the sibling lock and the handles used to stage and replace one reviewed profile. */
final class WindowsProfileUpdate implements ProfileUpdateFiles {
    public interface Advapi32 {
        int ConvertStringSecurityDescriptorToSecurityDescriptorW(@In byte[] sddl, int revision,
            @Out PointerByReference descriptor, Pointer size);
    }

    private static final Advapi32 SECURITY = LibraryLoader.create(Advapi32.class).stdcall().load("advapi32");
    // CPython 3.12.10 uses this protected, inheritable ACL for tempfile.mkdtemp's 0700 directory.
    private static final byte[] PRIVATE_DIRECTORY_ACL =
        "D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;OW)\0".getBytes(StandardCharsets.UTF_16LE);
    private static final int GENERIC_READ = 0x80000000, GENERIC_WRITE = 0x40000000, DELETE = 0x10000;
    private static final int SHARE_READ_WRITE = 3, SHARE_READ_WRITE_DELETE = 7;
    private static final int FILE_OPEN = 1, FILE_CREATE = 2, FILE_OPEN_IF = 3;
    private static final int FILE_DIRECTORY_FILE = 1, FILE_NON_DIRECTORY_FILE = 0x40;
    private final String name;
    private final WindowsPackDirectory parent;
    private Pointer lock;
    private WindowsPackDirectory folder;
    private WindowsProfileStage stage;
    private boolean committed;

    WindowsProfileUpdate(Path realParent, String fileName) throws IOException {
        WindowsProfileDirectory.requireLeaf(fileName);
        name = fileName;
        String lockName = "." + name + ".lock";
        WindowsProfileDirectory.requireLeaf(lockName);
        parent = WindowsPackDirectory.openProfileRoot(realParent, WindowsProfileDirectory.FILE_TRAVERSE);
        try {
            lock = WindowsProfileDirectory.openRelative(parent.rootHandle, lockName, GENERIC_READ | GENERIC_WRITE,
                SHARE_READ_WRITE, FILE_OPEN_IF, FILE_NON_DIRECTORY_FILE, null, "open the profile lock");
            WindowsPackDirectory.requireRegularProfileFile(lock);
            acquireLock(lock);
            parent.verifyPublicationRoot();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void acquireLock(Pointer file) throws IOException {
        var runtime = file.getRuntime();
        // A zeroed OVERLAPPED selects offset zero and a null event on this synchronous file handle.
        Pointer offset = Memory.allocateDirect(runtime, 3 * runtime.addressSize() + Long.BYTES, true);
        final int exclusiveFailImmediately = 3, errorLockViolation = 33;
        while (!Thread.currentThread().isInterrupted()) {
            if (WindowsPackDirectory.API.LockFileEx(file, exclusiveFailImmediately, 0, 1, 0, offset) != 0) {
                return;
            }
            int error = runtime.getLastError();
            if (error != errorLockViolation) {
                throw new IOException("Couldn't acquire the profile lock (Windows error " + error + ").");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        throw new InterruptedIOException("Interrupted while waiting for the profile lock.");
    }

    @Override
    public Original readOriginal() throws IOException {
        parent.verifyPublicationRoot();
        Pointer file = WindowsProfileDirectory.openRelative(parent.rootHandle, name, GENERIC_READ,
            SHARE_READ_WRITE_DELETE, FILE_OPEN, FILE_NON_DIRECTORY_FILE, null, "open the profile");
        try (Closeable close = () -> WindowsPackDirectory.closeHandle(file)) {
            WindowsPackDirectory.requireRegularProfileFile(file);
            return Original.from(WindowsPackDirectory.readAll(file), 0, WindowsPackDirectory.fileId(file));
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        parent.verifyPublicationRoot();
        var descriptor = new PointerByReference();
        if (SECURITY.ConvertStringSecurityDescriptorToSecurityDescriptorW(PRIVATE_DIRECTORY_ACL, 1, descriptor, null) == 0) {
            throw new IOException("Couldn't set private permissions for the profile staging directory.");
        }
        Pointer acl = descriptor.getValue();
        try (Closeable release = () -> freeDescriptor(acl)) {
            String directoryName = "." + name + ".stage-" + UUID.randomUUID();
            WindowsProfileDirectory.requireLeaf(directoryName);
            Pointer opened = WindowsProfileDirectory.openRelative(parent.rootHandle, directoryName,
                WindowsProfileDirectory.FILE_TRAVERSE | DELETE, SHARE_READ_WRITE_DELETE, FILE_CREATE,
                FILE_DIRECTORY_FILE, acl, "create the private profile staging directory");
            folder = new WindowsPackDirectory(parent.root.resolve(directoryName), opened);
        }
        stage = new WindowsProfileStage(folder);
        stage.write(bytes);
    }

    private static void freeDescriptor(Pointer descriptor) throws IOException {
        if (WindowsPackDirectory.API.LocalFree(descriptor) != null) {
            throw new IOException("Couldn't release the staging directory's security descriptor.");
        }
    }

    @Override
    public void preserveMode(int mode) {
        // The staging file inherits its access rules from the private directory.
    }

    @Override
    public void force() throws IOException {
        stage.force();
    }

    @Override
    public void verify(Original original) throws IOException {
        if (!readOriginal().equals(original)) {
            throw new IOException("The selected profile changed. Rerun the profile diff.");
        }
        stage.verify();
    }

    @Override
    public void replace() throws IOException {
        stage.replace(parent, name);
        committed = true;
    }

    @Override
    public void syncDirectory() {
        // The Windows publication contract flushes the staged file before replacement.
    }

    @Override
    public void discard() throws IOException {
        var directory = folder;
        var file = stage;
        folder = null;
        stage = null;
        if (directory != null) {
            // Close the file before removing its empty directory. Unexpected entries survive.
            try (directory; Closeable remove = () -> {
                directory.verifyPublicationRoot();
                WindowsProfileStage.deleteOnClose(directory.rootHandle);
            }; file) {
                if (file != null && !committed) {
                    file.verify();
                    file.discard();
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        Pointer owned = lock;
        lock = null;
        try (parent; Closeable release = () -> {
            if (owned != null) {
                WindowsPackDirectory.closeHandle(owned);
            }
        }) {
            discard();
        }
    }
}
