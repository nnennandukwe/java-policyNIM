package io.policynim.policy.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Holds the selected policy root while a manifest and its declared files are read. */
abstract class PackDirectory implements AutoCloseable {

    final Path root;
    private boolean closed;

    PackDirectory(Path root) {
        this.root = root;
    }

    static PackDirectory open(Path canonicalRoot) throws IOException {
        if (!canonicalRoot.isAbsolute() || canonicalRoot.getParent() == null
                || !Files.readAttributes(canonicalRoot, BasicFileAttributes.class, NOFOLLOW_LINKS).isDirectory()) {
            throw new IOException("Select a canonical policy directory below the filesystem root.");
        }
        try {
            return canonicalRoot.getFileSystem().getSeparator().equals("\\")
                ? new WindowsPackDirectory(canonicalRoot) : new PosixPackDirectory(canonicalRoot);
        } catch (LinkageError | RuntimeException ex) {
            throw new ReaderFailure(Failure.UNAVAILABLE, ex);
        }
    }

    static final class ResolvedFile {
        private final PackDirectory directory;
        private final Path lexical;
        private final Path identity;
        private final BasicFileAttributes attributes;

        private ResolvedFile(PackDirectory directory, Path lexical, Path identity, BasicFileAttributes attributes) {
            this.directory = directory;
            this.lexical = lexical;
            this.identity = identity;
            this.attributes = attributes;
        }

        boolean sameEntry(ResolvedFile other) {
            return directory == other.directory && identity.equals(other.identity)
                && Objects.equals(attributes.fileKey(), other.attributes.fileKey())
                && attributes.creationTime().equals(other.attributes.creationTime());
        }

        Path identity() {
            return identity;
        }
    }

    enum Failure {
        UNAVAILABLE("Couldn't initialize the policy pack file reader. Check native library access and retry."),
        ROOT_CHANGED("The selected pack directory changed during inspection. Retry with a stable policy root."),
        MEMBER_CHANGED("The selected pack member changed during inspection. Retry with stable policy files.");

        final String message;

        Failure(String message) {
            this.message = message;
        }
    }

    static final class ReaderFailure extends IOException {
        final Failure reason;

        ReaderFailure(Failure reason, Throwable cause) {
            super(reason.message, cause);
            this.reason = reason;
        }
    }

    static final class OutsideRootException extends IOException {
        private OutsideRootException() {
            super("Pack members must stay inside their selected directory.");
        }
    }

    static final class NotRegularFileException extends IOException {
        private NotRegularFileException() {
            super("Pack members must resolve to regular files.");
        }
    }

    final ResolvedFile resolve(String normalizedMember) throws IOException {
        checkOpen();
        if (normalizedMember.isEmpty() || normalizedMember.indexOf('\\') >= 0 || normalizedMember.indexOf(':') >= 0
                || normalizedMember.indexOf('\0') >= 0) {
            throw new IOException("Pack members must name files inside their selected directory.");
        }
        for (String component : normalizedMember.split("/", -1)) {
            if (component.isEmpty() || component.equals("..") || component.equals(".")) {
                throw new IOException("Pack members must name files inside their selected directory.");
            }
        }
        Path lexical = memberPath(normalizedMember);
        Path physical;
        try {
            physical = lexical.toRealPath();
        } catch (NoSuchFileException missing) {
            verifyRoot();
            requireMember(expandLinks(root, root.relativize(lexical), new HashSet<>()));
            throw missing;
        }
        requireMember(physical);
        var attributes = Files.readAttributes(physical, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new NotRegularFileException();
        }
        Path identity = memberIdentity(lexical, physical);
        requireMember(identity);
        return new ResolvedFile(this, lexical, identity, attributes);
    }

    final byte[] read(ResolvedFile member) throws IOException {
        checkOpen();
        if (member.directory != this) {
            throw new IOException("The resolved file belongs to a different policy pack.");
        }
        Path physical;
        try {
            physical = member.lexical.toRealPath();
        } catch (NoSuchFileException missing) {
            verifyRoot();
            throw missing;
        }
        requireMember(physical);
        if (!member.identity.equals(memberIdentity(member.lexical, physical))) {
            throw new ReaderFailure(Failure.MEMBER_CHANGED, null);
        }
        return readRegular(physical);
    }

    final void requireMember(Path path) throws IOException {
        if (!path.isAbsolute() || path.equals(root)) {
            throw new IOException("A pack member must resolve to a file below the selected directory.");
        }
        if (!path.startsWith(root)) {
            throw new OutsideRootException();
        }
    }

    static Path expandLinks(Path base, Path components, Set<Path> active) throws IOException {
        Path current = components.isAbsolute() ? components.getRoot() : base;
        for (Path part : components) {
            if (part.equals(Path.of(".."))) {
                current = current.getParent() == null ? current : current.getParent();
            } else if (!part.equals(Path.of("."))) {
                Path candidate = current.resolve(part);
                if (Files.isSymbolicLink(candidate)) {
                    if (!active.add(candidate)) {
                        throw new IOException("A policy pack member contains a circular symbolic link.");
                    }
                    current = expandLinks(current, Files.readSymbolicLink(candidate), active);
                    active.remove(candidate);
                } else {
                    current = candidate;
                }
            }
        }
        return current;
    }

    final String relativeName(Path absoluteEntry) throws IOException {
        checkOpen();
        requireMember(absoluteEntry);
        if (!absoluteEntry.equals(absoluteEntry.normalize())) {
            throw new IOException("Expected a normalized directory entry.");
        }
        return lexicalName(absoluteEntry);
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("The policy pack directory is closed.");
        }
    }

    Path memberPath(String normalizedMember) {
        return root.resolve(normalizedMember);
    }

    Path memberIdentity(Path lexical, Path physical) throws IOException {
        return physical;
    }

    String lexicalName(Path absoluteEntry) throws IOException {
        return root.relativize(absoluteEntry).toString().replace('\\', '/');
    }

    abstract void verifyRoot() throws IOException;

    abstract byte[] readRegular(Path physical) throws IOException;

    abstract void closeRoot() throws IOException;

    @Override
    public final void close() throws IOException {
        if (!closed) {
            closed = true;
            closeRoot();
        }
    }
}
