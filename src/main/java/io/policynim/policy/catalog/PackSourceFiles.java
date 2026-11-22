package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Selects declared pack members before parsing their content and lists other Markdown by name. */
final class PackSourceFiles {
    private static final String MANIFEST_CHANGED = "pack.toml changed during inspection. Retry with stable policy files.";

    @FunctionalInterface
    interface Consumer {
        void accept(String path, String sourcePath, byte[] bytes, PolicyPackManifest manifest, PolicyPackManifest.Member member);
    }

    record ReadResult(PolicyPackManifest manifest, List<String> unlisted) { }

    private record Selection(PolicyPackManifest.Member member, PackDirectory.ResolvedFile file) { }

    private PackSourceFiles() { }

    static ReadResult read(Path root, Consumer consumer) throws IOException {
        try (PackDirectory directory = PackDirectory.open(root)) {
            PackDirectory.ResolvedFile manifestFile;
            byte[] manifestBytes;
            try {
                manifestFile = directory.resolve("pack.toml");
                manifestBytes = directory.read(manifestFile);
            } catch (PackDirectory.OutsideRootException outside) {
                throw new InvalidPolicyDocumentException("pack.toml must stay within the policy root.", outside);
            } catch (IOException error) {
                throw publicFailure(error, "Couldn't read pack.toml as UTF-8 TOML. Check file access and retry.");
            }
            PolicyPackManifest manifest = PolicyPackManifest.parse(manifestBytes);
            Set<Path> identities = new HashSet<>();
            var selected = new TreeMap<String, Selection>((left, right) ->
                Arrays.compare(left.split("/"), right.split("/"), SourceCatalog::compareCodePoints));
            for (int index = 0; index < manifest.policies().size(); index++) {
                var member = manifest.policies().get(index);
                String field = "pack.toml policies." + index + ".path";
                String path = normalizedPath(member.path(), field);
                PackDirectory.ResolvedFile file;
                try {
                    file = directory.resolve(path);
                } catch (PackDirectory.OutsideRootException outside) {
                    throw new InvalidPolicyDocumentException(field + " must name a Markdown member within the pack.", outside);
                } catch (NoSuchFileException | PackDirectory.NotRegularFileException missing) {
                    throw new InvalidPolicyDocumentException(field + " doesn't exist.", missing);
                } catch (IOException inaccessible) {
                    throw publicFailure(inaccessible, field + " must name a regular Markdown file within the pack. Check paths and permissions.");
                }
                if (!identities.add(file.identity())) {
                    throw new InvalidPolicyDocumentException(field + " repeats a member.");
                }
                selected.put(path, new Selection(member, file));
            }
            for (var entry : selected.entrySet()) {
                String path = entry.getKey();
                String sourcePath = root.getFileName() + "/" + path;
                byte[] content;
                try {
                    content = directory.read(entry.getValue().file());
                } catch (IOException unreadable) {
                    throw publicFailure(unreadable, "Could not read policy document " + sourcePath + ". Check file access and retry.");
                }
                consumer.accept(path, sourcePath, content, manifest, entry.getValue().member());
            }
            directory.verifyRoot();
            List<String> unlisted;
            try {
                unlisted = unlistedMarkdown(root, directory, selected.keySet());
            } catch (IOException failure) {
                directory.verifyRoot();
                throw failure;
            }
            verifyManifest(directory, manifestFile, manifestBytes);
            return new ReadResult(manifest, List.copyOf(unlisted));
        } catch (IOException failure) {
            throw publicFailure(failure, "Couldn't inspect the selected policy pack. Check directory access and retry.");
        }
    }

    private static void verifyManifest(PackDirectory directory, PackDirectory.ResolvedFile original, byte[] bytes) {
        try {
            var current = directory.resolve("pack.toml");
            if (!original.sameEntry(current) || !Arrays.equals(bytes, directory.read(current))) {
                throw new InvalidPolicyDocumentException(MANIFEST_CHANGED);
            }
        } catch (IOException failure) {
            if (failure instanceof PackDirectory.ReaderFailure reader && reader.reason == PackDirectory.Failure.ROOT_CHANGED) {
                throw publicFailure(reader, MANIFEST_CHANGED);
            }
            throw new InvalidPolicyDocumentException(MANIFEST_CHANGED, failure);
        }
    }

    private static InvalidPolicyDocumentException publicFailure(IOException error, String fallback) {
        String message = error instanceof PackDirectory.ReaderFailure reader ? reader.reason.message : fallback;
        return new InvalidPolicyDocumentException(message, error);
    }

    static String normalizedPath(String raw, String field) {
        List<String> components = Arrays.stream(raw.split("/"))
            .filter(part -> !part.isEmpty() && !part.equals(".")).toList();
        String name = components.isEmpty() ? "" : components.getLast();
        if (raw.startsWith("/") || raw.contains("\\") || raw.contains(":") || components.contains("..")
                || !name.endsWith(".md") || name.equals(".md") || name.equals("TEMPLATE.md")) {
            throw new InvalidPolicyDocumentException(field + " must name a Markdown member within the pack.");
        }
        return String.join("/", components);
    }

    private static List<String> unlistedMarkdown(Path root, PackDirectory directory, Set<String> selected) throws IOException {
        List<String> unlisted = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void record(Path entry) throws IOException {
                String name = entry.getFileName().toString();
                if (name.endsWith(".md") && !name.equals("TEMPLATE.md")) {
                    String path = directory.relativeName(entry);
                    if (!selected.contains(path)) {
                        unlisted.add(path);
                    }
                }
            }

            @Override
            public FileVisitResult preVisitDirectory(Path entry, BasicFileAttributes attributes) throws IOException {
                if (!entry.equals(root)) {
                    record(entry);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path entry, BasicFileAttributes attributes) throws IOException {
                record(entry);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path entry, IOException error) throws IOException {
                if (error instanceof AccessDeniedException && !entry.equals(root)) {
                    record(entry);
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }
        });
        unlisted.sort(SourceCatalog::compareCodePoints);
        return unlisted;
    }
}
