package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Pins selected packs and validates the full profile before creating its destination. */
final class ProfileInitializer {
    private ProfileInitializer() { }

    static PolicyProfile initialize(Path file, List<String> explicitPacks, String profileId) {
        if (!PolicyProfile.NAME.matcher(profileId).matches()) {
            throw new InvalidPolicyDocumentException("Invalid profile ID. Use a lowercase name of 1 to 128 characters.");
        }
        var absolute = file.toAbsolutePath();
        if (absolute.getFileName() == null) {
            throw new InvalidPolicyDocumentException("Choose a new file for the profile.");
        }
        try {
            Path parent = resolveParent(absolute.getParent());
            var pins = new ArrayList<PolicyProfile.Pin>();
            var validationPins = new ArrayList<PolicyProfile.Pin>();
            var ids = new HashSet<String>();
            if (explicitPacks.isEmpty()) {
                var pack = SourceCatalog.capturePack(PackLocation.Bundled.BASELINE);
                var pin = ProfileResolver.pin("builtin:baseline", pack);
                pins.add(pin);
                validationPins.add(pin);
            }
            for (String raw : explicitPacks) {
                Path root = localPackRoot(raw);
                var pack = SourceCatalog.capturePack(new PackLocation.Local(root));
                if (!ids.add(pack.packId())) {
                    throw new InvalidPolicyDocumentException("Invalid profile ID or repeated pack selection.");
                }
                String path = relativeSelector(parent, root);
                var pin = ProfileResolver.pin(path, pack);
                pins.add(pin);
                // An uncreated output parent can't be traversed to resolve its relative pins yet.
                validationPins.add(new PolicyProfile.Pin(posix(root), pin.packId(), pin.version(), pin.digest(), pin.policies()));
            }
            var output = parent.resolve(absolute.getFileName());
            var profile = profile(profileId, pins);
            ProfileResolver.resolve(output, profile(profileId, validationPins));
            new ProfilePublication().publish(output, InitialProfileToml.render(profile));
            return profile;
        } catch (IOException | InvalidPathException error) {
            throw new InvalidPolicyDocumentException(
                "Couldn't access profile inputs or output. Check paths, permissions, and UTF-8 files.", error);
        }
    }

    private static PolicyProfile profile(String id, List<PolicyProfile.Pin> pins) {
        return new PolicyProfile(id, List.copyOf(pins), List.of(), List.of(), List.of());
    }

    private static Path localPackRoot(String raw) throws IOException {
        String normalized = PackLocation.normalize(raw);
        try {
            return Path.of(normalized).toRealPath();
        } catch (NoSuchFileException missing) {
            throw new InvalidPolicyDocumentException(
                "Selected pack directory doesn't exist. Pass the directory containing pack.toml with --pack.", missing);
        }
    }

    // Resolve existing ancestors in order, including symlinks that precede a missing directory.
    private static Path resolveParent(Path absolute) throws IOException {
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            if (part.toString().equals("..")) {
                current = current.getParent() == null ? current : current.getParent();
            } else if (!part.toString().equals(".")) {
                current = current.resolve(part);
                try {
                    current = current.toRealPath();
                } catch (NoSuchFileException missing) {
                    if (Files.isSymbolicLink(current)) {
                        throw missing;
                    }
                }
            }
        }
        return current;
    }

    private static String relativeSelector(Path parent, Path root) {
        String selector;
        if (parent.getRoot().equals(root.getRoot())) {
            selector = posix(parent.relativize(root));
            if (selector.isEmpty()) {
                selector = ".";
            }
        } else {
            selector = posix(root);
        }
        return PackLocation.normalize(selector);
    }

    private static String posix(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }
}
