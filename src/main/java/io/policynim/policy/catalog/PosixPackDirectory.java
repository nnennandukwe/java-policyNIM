package io.policynim.policy.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;

import jnr.constants.platform.Errno;
import jnr.constants.platform.OpenFlags;
import jnr.ffi.LibraryLoader;
import jnr.ffi.Platform;
import jnr.ffi.annotations.In;
import jnr.posix.FileStat;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class PosixPackDirectory extends PackDirectory {

    public interface DirectoryCalls {
        int open(@In byte[] path, int flags);
        int openat(int parent, @In byte[] component, int flags);
    }

    static final POSIX POSIX_API = POSIXFactory.getNativePOSIX();
    private static final DirectoryCalls CALLS = LibraryLoader.create(DirectoryCalls.class)
        .load(Platform.getNativePlatform().getStandardCLibraryName());
    private static final int DIRECTORY = flags(OpenFlags.O_DIRECTORY);
    private static final int REGULAR = flags(OpenFlags.O_NONBLOCK);
    private final String rawRoot;
    private final int rootFd;
    private final FileStat rootStat;

    PosixPackDirectory(Path root) throws IOException {
        this(root, checked(CALLS.open(terminated(octets(rawPath(root))), DIRECTORY)));
    }

    static PosixPackDirectory openFileSystemRoot(int flags) throws IOException {
        return new PosixPackDirectory(Path.of("/"), checked(CALLS.open(new byte[] {'/', 0}, flags)));
    }

    static byte[] nativeFileName(Path path) {
        String raw = rawPath(path);
        return terminated(octets(raw.substring(raw.lastIndexOf('/') + 1)));
    }

    /** Takes ownership of an already open directory descriptor, including on validation failure. */
    PosixPackDirectory(Path root, int descriptor) throws IOException {
        super(root);
        rootFd = descriptor;
        try {
            rawRoot = rawPath(root);
            rootStat = POSIX_API.allocateStat();
            checked(POSIX_API.fstat(rootFd, rootStat));
            verifyRoot();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                checked(POSIX_API.close(rootFd));
            } catch (IOException | RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    int descriptor() {
        return rootFd;
    }

    @Override
    void verifyRoot() throws IOException {
        Map<String, Object> attributes;
        try {
            attributes = Files.readAttributes(root, "unix:dev,ino", NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw new ReaderFailure(Failure.ROOT_CHANGED, missing);
        }
        if (((Number) attributes.get("dev")).longValue() != rootStat.dev()
                || ((Number) attributes.get("ino")).longValue() != rootStat.ino()) {
            throw new ReaderFailure(Failure.ROOT_CHANGED, null);
        }
    }

    private static int flags(OpenFlags type) {
        int result = 0;
        for (OpenFlags flag : new OpenFlags[] {OpenFlags.O_RDONLY, OpenFlags.O_CLOEXEC, OpenFlags.O_NOFOLLOW, type}) {
            if (!flag.defined()) {
                throw new IllegalStateException("The platform doesn't define a required file-open flag.");
            }
            result |= flag.intValue();
        }
        return result;
    }

    @Override
    Path memberPath(String member) {
        // The Unix JDK accepts percent-encoded native bytes even when its filename charset is ASCII.
        var uri = new StringBuilder("file://").append(rawRoot).append('/');
        HexFormat hex = HexFormat.of().withUpperCase();
        for (byte value : member.getBytes(StandardCharsets.UTF_8)) {
            if (value == '/') {
                uri.append('/');
            } else {
                hex.toHexDigits(uri.append('%'), value);
            }
        }
        return Path.of(URI.create(uri.toString()));
    }

    @Override
    Path memberIdentity(Path lexical, Path physical) throws IOException {
        // CPython's POSIX resolution expands links while preserving every ordinary component's spelling.
        return expandLinks(root, root.relativize(lexical), new HashSet<>());
    }

    @Override
    String lexicalName(Path entry) throws IOException {
        byte[] bytes = octets(rawPath(entry).substring(rawRoot.length() + 1));
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String rawPath(Path path) {
        String raw = path.toUri().getRawPath();
        return raw.endsWith("/") && raw.length() > 1 ? raw.substring(0, raw.length() - 1) : raw;
    }

    private static byte[] octets(String raw) {
        var decoded = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                decoded.write(HexFormat.fromHexDigits(raw, i + 1, i + 3));
                i += 2;
            } else {
                decoded.write(c);
            }
        }
        return decoded.toByteArray();
    }

    private static byte[] terminated(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length + 1);
    }

    @Override
    byte[] readRegular(Path physical) throws IOException {
        verifyRoot();
        String[] components = rawPath(physical).substring(rawRoot.length() + 1).split("/");
        int directory = rootFd;
        try {
            for (int i = 0; i < components.length - 1; i++) {
                int child = checked(CALLS.openat(directory, terminated(octets(components[i])), DIRECTORY));
                int previous = directory;
                directory = child;
                if (previous != rootFd) {
                    checked(POSIX_API.close(previous));
                }
            }
            byte[] filename = terminated(octets(components[components.length - 1]));
            return readDescriptor(checked(CALLS.openat(directory, filename, REGULAR)));
        } finally {
            if (directory != rootFd) {
                checked(POSIX_API.close(directory));
            }
        }
    }

    private static byte[] readDescriptor(int descriptor) throws IOException {
        try {
            var stat = POSIX_API.allocateStat();
            checked(POSIX_API.fstat(descriptor, stat));
            if (!stat.isFile()) {
                throw new IOException("Pack members must resolve to regular files.");
            }
            return readBytes(descriptor);
        } finally {
            checked(POSIX_API.close(descriptor));
        }
    }

    static byte[] readBytes(int descriptor) throws IOException {
        var result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (;;) {
            int count = POSIX_API.read(descriptor, buffer, buffer.length);
            if (count == 0) {
                return result.toByteArray();
            }
            if (count < 0 && POSIX_API.errno() == Errno.EINTR.intValue()) {
                continue;
            }
            result.write(buffer, 0, checked(count));
        }
    }

    private static int checked(int result) throws IOException {
        if (result < 0) {
            throw new IOException("Couldn't access the selected pack file.");
        }
        return result;
    }

    @Override
    void closeRoot() throws IOException {
        checked(POSIX_API.close(rootFd));
    }
}
