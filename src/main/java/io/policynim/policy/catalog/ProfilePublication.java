package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

/** Publishes a complete new profile by linking its forced staging file to an unused destination. */
class ProfilePublication {
    private static final Set<StandardOpenOption> CREATE = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    private static final FileAttribute<?> PRIVATE = PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rw-------"));

    void publish(Path output, String contents) throws IOException {
        Path parent = Files.createDirectories(output.getParent());
        Path stage = parent.resolve(".policynim-profile-" + UUID.randomUUID() + ".tmp");
        boolean owned = false;
        boolean published = false;
        Throwable failure = null;
        try {
            FileAttribute<?>[] attributes = parent.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[]{PRIVATE} : new FileAttribute<?>[0];
            try (var file = FileChannel.open(stage, CREATE, attributes)) {
                owned = true;
                write(file, StandardCharsets.UTF_8.encode(contents));
                force(file);
            }
            try {
                link(output, stage);
            } catch (FileAlreadyExistsException exists) {
                throw new InvalidPolicyDocumentException("Profile already exists. Choose a new path.", exists);
            } catch (UnsupportedOperationException unsupported) {
                throw new InvalidPolicyDocumentException(
                    "This filesystem can't publish a new profile using hard links. Choose a supported local directory.", unsupported);
            }
            published = true;
        } catch (IOException | RuntimeException error) {
            failure = error;
            throw error;
        } finally {
            if (owned) {
                try {
                    discard(stage);
                } catch (IOException | RuntimeException error) {
                    if (failure != null) {
                        failure.addSuppressed(error);
                    } else {
                        String message = published
                            ? "The profile was created, but staging cleanup failed. Inspect the created profile before retrying."
                            : "Profile staging cleanup failed. Check the output directory before retrying.";
                        throw new InvalidPolicyDocumentException(message, error);
                    }
                }
            }
        }
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
