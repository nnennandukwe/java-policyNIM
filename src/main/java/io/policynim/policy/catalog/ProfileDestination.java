package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Pins the existing parent before source capture and creates any missing directories after validation. */
final class ProfileDestination implements Closeable {
    private final ProfileDirectory directory;
    private final ArrayDeque<String> missing;
    private final Path path;
    private boolean closed;

    private ProfileDestination(ProfileDirectory directory, ArrayDeque<String> missing, Path path) {
        this.directory = directory;
        this.missing = missing;
        this.path = path;
    }

    static ProfileDestination select(Path output) throws IOException {
        if (output.getFileSystem() != FileSystems.getDefault()) {
            throw new InvalidPolicyDocumentException("Choose a profile destination on the local filesystem.");
        }
        Path absolute = output.toAbsolutePath();
        if (absolute.getFileName() == null) {
            throw new InvalidPolicyDocumentException("Choose a new file for the profile.");
        }
        Path existing = absolute.getRoot();
        var missing = new ArrayDeque<String>();
        for (Path part : absolute.getParent()) {
            String name = part.toString();
            if (name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                if (!missing.isEmpty()) {
                    missing.removeLast();
                } else if (existing.getParent() != null) {
                    existing = existing.getParent();
                }
            } else if (!missing.isEmpty()) {
                missing.add(name);
            } else {
                Path next = existing.resolve(part);
                try {
                    existing = next.toRealPath();
                } catch (NoSuchFileException absent) {
                    if (Files.isSymbolicLink(next)) {
                        throw absent;
                    }
                    missing.add(name);
                }
            }
        }
        Path parent = existing;
        for (String name : missing) {
            parent = parent.resolve(name);
        }
        Path path = parent.resolve(absolute.getFileName());
        try {
            ProfileDirectory directory = existing.getFileSystem().getSeparator().equals("\\")
                ? new WindowsProfileDirectory(existing) : new PosixProfileDirectory(existing);
            return new ProfileDestination(directory, missing, path);
        } catch (LinkageError error) {
            throw new IOException("Couldn't initialize native profile publication.", error);
        }
    }

    Path path() {
        return path;
    }

    ProfileStage stage() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        try {
            while (!missing.isEmpty()) {
                directory.descend(missing.getFirst());
                missing.removeFirst();
            }
        } catch (IOException error) {
            throw new InvalidPolicyDocumentException(
                "Couldn't open or create the selected profile directory. Check its location and write access, then retry.",
                error);
        }
        return directory.stage();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            directory.close();
        }
    }
}
