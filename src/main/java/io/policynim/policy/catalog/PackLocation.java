package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;

/** Resolves each pin to a local directory identity or a fixed classpath pack. */
sealed interface PackLocation {

    record Local(Path realRoot) implements PackLocation { }

    enum Bundled implements PackLocation {
        BASELINE, SECURITY, API, DATA, OBSERVABILITY, RELIABILITY, SUPPLY_CHAIN, TESTING;

        String topic() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        private static Bundled select(String spelling) {
            for (Bundled bundled : values()) {
                if (spelling.equals("builtin:" + bundled.topic())) {
                    return bundled;
                }
            }
            throw new InvalidPolicyDocumentException("Select a supported bundled policy reference.");
        }
    }

    static String normalize(String value) {
        if (value.startsWith("builtin:")) {
            Bundled.select(value);
            return value;
        }
        boolean windows = File.separatorChar == '\\';
        String normalized = value.replace('\\', '/');
        boolean absoluteDrive = windows && normalized.matches("[a-zA-Z]:/[^:]*");
        if (normalized.startsWith("//") || value.chars().anyMatch(ch -> ch < 32 || ch == 127)
                || value.contains(":") && !absoluteDrive || !windows && value.contains("\\")) {
            throw new InvalidPolicyDocumentException(
                "Pack paths must name local directories. Network shares and remote schemes aren't allowed.");
        }
        return normalized;
    }

    static PackLocation resolve(Path profileFile, String pinPath) {
        String normalized = normalize(pinPath);
        if (normalized.startsWith("builtin:")) {
            return Bundled.select(normalized);
        }
        try {
            return new Local(profileFile.toAbsolutePath().getParent().resolve(normalized).toRealPath());
        } catch (NoSuchFileException missing) {
            throw missingManifest();
        } catch (IOException | InvalidPathException error) {
            throw new InvalidPolicyDocumentException(
                "Selected pack path couldn't be resolved. Check local paths, symlinks, and permissions.", error);
        }
    }

    static InvalidPolicyDocumentException missingManifest() {
        return new InvalidPolicyDocumentException(
            "Selected packs require pack.toml. Use POLICYNIM_CORPUS_DIR for a legacy directory.");
    }
}
