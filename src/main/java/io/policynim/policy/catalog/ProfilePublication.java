package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Publishes a complete new profile by linking its forced staging file to an unused destination. */
class ProfilePublication {
    private static final Set<StandardOpenOption> CREATE = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    private static final FileAttribute<?> PRIVATE = PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rw-------"));

    void publish(Path output, String contents) throws IOException {
        Path parent = Files.createDirectories(output.getParent());
        var stage = new Stage(parent);
        try (var directory = holdDirectory(parent); var channel = stage.create()) {
            stage.establishOwnership();
            // Keep both handles open through publication and cleanup so their file IDs can't be reused.
            try (stage) {
                write(channel, StandardCharsets.UTF_8.encode(contents));
                force(channel);
                stage.verifyOwnership();
                try {
                    link(output, stage.path);
                } catch (FileAlreadyExistsException exists) {
                    throw new InvalidPolicyDocumentException("Profile already exists. Choose a new path.", exists);
                } catch (UnsupportedOperationException unsupported) {
                    throw new InvalidPolicyDocumentException(
                        "This filesystem can't publish a new profile using hard links. Choose a supported local directory.", unsupported);
                }
                stage.published = true;
            }
        } catch (IOException | RuntimeException error) {
            if (stage.published) {
                throw new InvalidPolicyDocumentException(
                    "The profile was created, but staging cleanup failed. Inspect the created profile before retrying.", error);
            }
            throw error;
        }
    }

    private static Closeable holdDirectory(Path directory) throws IOException {
        if (directory.getFileSystem().getSeparator().equals("\\")) {
            try {
                return new WindowsPackDirectory(directory)::close;
            } catch (LinkageError error) {
                throw new IOException("Couldn't initialize the Windows directory reader.", error);
            }
        }
        return FileChannel.open(directory, StandardOpenOption.READ, NOFOLLOW_LINKS);
    }

    private final class Stage implements AutoCloseable {
        final Path path;
        Object parent;
        Object file;
        boolean published;

        Stage(Path directory) {
            path = directory.resolve(".policynim-profile-" + UUID.randomUUID() + ".tmp");
        }

        FileChannel create() throws IOException {
            parent = fileKey(path.getParent(), true);
            FileAttribute<?>[] attributes = path.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[]{PRIVATE} : new FileAttribute<?>[0];
            return FileChannel.open(path, CREATE, attributes);
        }

        void establishOwnership() {
            try {
                file = fileKey(path, false);
            } catch (IOException error) {
                throw new InvalidPolicyDocumentException("Couldn't verify the new staging file. "
                    + "Inspect the output directory before retrying.", error);
            }
        }

        void verifyOwnership() {
            String changed = "The profile staging file or its directory changed. Inspect the output directory before retrying.";
            try {
                if (!parent.equals(fileKey(path.getParent(), true)) || !file.equals(fileKey(path, false))) {
                    throw new InvalidPolicyDocumentException(changed);
                }
            } catch (IOException error) {
                throw new InvalidPolicyDocumentException(changed, error);
            }
        }

        @Override
        public void close() throws IOException {
            verifyOwnership();
            discard(path);
        }
    }

    private static Object fileKey(Path path, boolean directory) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
            throw new IOException("The profile staging entry changed type.");
        }
        if (attributes.fileKey() != null) {
            return attributes.fileKey();
        }
        if (path.getFileSystem().getSeparator().equals("\\")) {
            return WindowsPackDirectory.publicationFileId(path);
        }
        throw new IOException("The filesystem didn't identify the profile staging entry.");
    }

    void write(FileChannel file, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            file.write(bytes);
        }
    }

    void force(FileChannel file) throws IOException {
        file.force(true);
    }

    void link(Path output, Path stage) throws IOException {
        Files.createLink(output, stage);
    }

    void discard(Path stage) throws IOException {
        Files.delete(stage);
    }
}
