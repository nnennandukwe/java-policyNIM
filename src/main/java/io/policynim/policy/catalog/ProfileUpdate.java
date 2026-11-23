package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/** Serializes reviewed pin changes and reports whether publication took effect. */
final class ProfileUpdate {
    private static final String SYNC_WARNING = "Profile pins changed, but directory sync failed. "
        + "Reread the profile before retrying or restarting.";
    private static final String CLEANUP_WARNING = "Profile staging cleanup couldn't finish. "
        + "Preserve unexpected entries and inspect the profile before retrying.";

    @FunctionalInterface
    interface Opener {
        ProfileUpdateFiles open(Path parent, String name) throws IOException;
    }

    private ProfileUpdate() { }

    static ObjectNode apply(Path file, String token) {
        return apply(file, token, ProfileUpdate::open);
    }

    static ProfileUpdateFiles open(Path parent, String name) throws IOException {
        try {
            return parent.getFileSystem().getSeparator().equals("\\")
                ? new WindowsProfileUpdate(parent, name) : new PosixProfileUpdate(parent, name);
        } catch (LinkageError error) {
            throw new IOException("Couldn't initialize native profile updates.", error);
        }
    }

    static ObjectNode apply(Path file, String token, Opener opener) {
        var name = file.getFileName();
        if (file.getFileSystem() != FileSystems.getDefault() || name == null || name.toString().isEmpty()
                || name.toString().equals(".") || name.toString().equals("..")) {
            throw new InvalidPolicyDocumentException("Choose a profile file on the local filesystem.");
        }
        ObjectNode published = null;
        try {
            Path parent = file.toAbsolutePath().getParent().toRealPath();
            Path target = parent.resolve(name);
            try (var files = opener.open(parent, name.toString()); Closeable cleanup = files::discard) {
                published = replaceReviewed(target, token, files);
                try {
                    files.syncDirectory();
                } catch (IOException | RuntimeException failure) {
                    published.withArray("warnings").add(SYNC_WARNING);
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (published != null) {
                published.withArray("warnings").add(CLEANUP_WARNING);
            } else if (failure instanceof RuntimeException unchecked) {
                throw unchecked;
            } else {
                throw new InvalidPolicyDocumentException(
                    "Couldn't access profile inputs or output. Check paths, permissions, and UTF-8 files.", failure);
            }
        }
        return published;
    }

    private static ObjectNode replaceReviewed(Path target, String token, ProfileUpdateFiles files) throws IOException {
        var review = ProfileResolver.review(target);
        var report = review.report();
        if (!report.required("review_digest").asText().equals(token)) {
            throw new InvalidPolicyDocumentException("Review digest changed. Rerun profile diff.");
        }
        var invalid = report.required("validation_error");
        if (!invalid.isNull()) {
            throw new InvalidPolicyDocumentException(invalid.asText());
        }
        if (Files.isSymbolicLink(target)) {
            throw new InvalidPolicyDocumentException("Profile updates won't replace a symlink. Use its real path.");
        }
        var original = files.readOriginal();
        if (!original.text().equals(review.document().text())) {
            throw new InvalidPolicyDocumentException("Profile changed before staging. Rerun the profile diff.");
        }
        String output = ProfilePinEditor.update(review.document(), review.current());
        files.write(output.replace("\n", System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        files.preserveMode(original.mode());
        files.force();

        var finalReview = ProfileResolver.review(target);
        if (!finalReview.document().text().equals(original.text())
                || !finalReview.report().required("review_digest").asText().equals(token)) {
            throw new InvalidPolicyDocumentException("Profile or sources changed. Rerun profile diff.");
        }
        files.verify(original);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Profile update was interrupted.");
        }
        files.replace();
        return report;
    }
}
