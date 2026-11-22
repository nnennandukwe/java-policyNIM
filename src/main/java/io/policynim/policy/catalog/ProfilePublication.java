package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/** Publishes a complete new profile in the directory opened for this operation. */
class ProfilePublication {
    /** The caller resolves existing parent directories to their real paths before publication creates any missing ones. */
    void publish(Path output, String contents) throws IOException {
        if (output.getFileSystem() != FileSystems.getDefault()) {
            throw new InvalidPolicyDocumentException("Choose a profile destination on the local filesystem.");
        }
        Path parent = Files.createDirectories(output.getParent());
        boolean published = false;
        boolean located = false;
        try (var stage = open(parent); Closeable cleanup = () -> discard(stage)) {
            write(stage, contents.getBytes(StandardCharsets.UTF_8));
            force(stage);
            verify(stage);
            try {
                commit(stage, output.getFileName().toString());
            } catch (FileAlreadyExistsException exists) {
                throw new InvalidPolicyDocumentException("Profile already exists. Choose a new path.", exists);
            }
            published = true;
            stage.verifyDirectory();
            located = true;
        } catch (IOException | RuntimeException error) {
            if (published) {
                String message = located
                    ? "The profile was created, but staging cleanup failed. Inspect the created profile before retrying."
                    : "The profile was created in the originally opened directory, but its location couldn't be "
                        + "verified. Inspect that directory before retrying.";
                throw new InvalidPolicyDocumentException(message, error);
            }
            if (error.getSuppressed().length > 0 && !(error instanceof InvalidPolicyDocumentException)) {
                throw new InvalidPolicyDocumentException("The profile wasn't published. An unverified staging entry may remain. "
                    + "Inspect the output directory before retrying.", error);
            }
            throw error;
        }
    }

    private static ProfileStage open(Path directory) throws IOException {
        try {
            return directory.getFileSystem().getSeparator().equals("\\")
                ? new WindowsProfileStage(directory) : new PosixProfileStage(directory);
        } catch (LinkageError error) {
            throw new IOException("Couldn't initialize native profile publication.", error);
        }
    }

    void write(ProfileStage stage, byte[] bytes) throws IOException {
        stage.write(bytes);
    }

    void force(ProfileStage stage) throws IOException {
        stage.force();
    }

    void verify(ProfileStage stage) throws IOException {
        try {
            stage.verify();
        } catch (IOException error) {
            throw new InvalidPolicyDocumentException(
                "The profile staging file or its directory changed. Inspect the output directory before retrying.", error);
        }
    }

    void commit(ProfileStage stage, String outputName) throws IOException {
        stage.publish(outputName);
    }

    void discard(ProfileStage stage) throws IOException {
        stage.discard();
    }
}
