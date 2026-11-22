package io.policynim.policy.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
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
            throw new IOException("Couldn't initialize the policy pack file reader.", ex);
        }
    }

    static final class ResolvedFile {
        private final PackDirectory directory;
        private final Path lexical;
        private final Path identity;

        private ResolvedFile(PackDirectory directory, Path lexical, Path identity) {
            this.directory = directory;
            this.lexical = lexical;
            this.identity = identity;
        }

        Path identity() {
            return identity;
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
            requireMember(expandLinks(root, root.relativize(lexical), new HashSet<>()));
            throw missing;
        }
        requireMember(physical);
        if (!Files.readAttributes(physical, BasicFileAttributes.class, NOFOLLOW_LINKS).isRegularFile()) {
            throw new NotRegularFileException();
        }
        Path identity = memberIdentity(lexical, physical);
        requireMember(identity);
        return new ResolvedFile(this, lexical, identity);
    }

    final byte[] read(ResolvedFile member) throws IOException {
        checkOpen();
        if (member.directory != this) {
            throw new IOException("The resolved file belongs to a different policy pack.");
        }
        Path physical = member.lexical.toRealPath();
        requireMember(physical);
        if (!member.identity.equals(memberIdentity(member.lexical, physical))) {
            throw new IOException("The selected pack member changed during inspection.");
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
