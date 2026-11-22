package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

final class SourceDirectory {

    private static final String ROOT_HINT = " Pass a directory of policy Markdown files with --corpus-root=<path>.";

    @FunctionalInterface
    interface SourceFile {
        void accept(String path, String sourcePath, byte[] content);
    }

    private final Path selection;
    private final Path root;

    private SourceDirectory(Path selection, Path root) {
        this.selection = selection;
        this.root = root;
    }

    static SourceDirectory open(Path selected) throws IOException {
        if (selected == null || selected.toString().isBlank()) {
            throw new InvalidPolicyDocumentException("Select a policy source directory with --corpus-root.");
        }
        Path root;
        try {
            root = selected.toRealPath();
        } catch (NoSuchFileException ex) {
            throw new InvalidPolicyDocumentException("Policy root " + selected + " doesn't exist." + ROOT_HINT, ex);
        }
        if (root.getParent() == null) {
            throw new InvalidPolicyDocumentException("Select a policy directory below the filesystem root.");
        }
        if (!attributes(root).isDirectory()) {
            if (root.getFileName().toString().endsWith(".toml")) {
                throw new InvalidPolicyDocumentException("Pass the directory containing pack.toml with --corpus-root=<path>. "
                    + "Source inspection requires a directory. A profile file isn't supported here.");
            }
            throw new InvalidPolicyDocumentException("Policy root " + selected + " isn't a directory." + ROOT_HINT);
        }
        return new SourceDirectory(selected, root);
    }

    Path root() {
        return root;
    }

    boolean hasManifest() throws IOException {
        try {
            attributes(root.resolve("pack.toml"));
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    void verifySelection(boolean expectedManifest) throws IOException {
        if (!selection.toRealPath().equals(root) || hasManifest() != expectedManifest) {
            throw changed(root.getFileName().toString());
        }
    }

    void readPlainFiles(SourceFile reader) throws IOException {
        visit(root, reader);
    }

    private void visit(Path directory, SourceFile reader) throws IOException {
        BasicFileAttributes before = attributes(directory);
        if (!before.isDirectory() || !directory.toRealPath().equals(directory)) {
            throw changed(sourcePath(directory));
        }
        if (directory.equals(root) && hasManifest()) {
            throw changed("pack.toml");
        }
        List<Path> children = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory)) {
            stream.forEach(children::add);
        }
        children.sort((first, second) -> SourceCatalog.compareCodePoints(
            first.getFileName().toString(), second.getFileName().toString()));
        for (Path child : children) {
            String filename = child.getFileName().toString();
            String sourcePath = sourcePath(child);
            try {
                if (filename.endsWith(".md") && !filename.equals("TEMPLATE.md")) {
                    reader.accept(posix(root.relativize(child)), sourcePath, capture(child));
                } else if (attributes(child).isDirectory()) {
                    visit(child, reader);
                }
            } catch (IOException | DirectoryIteratorException ex) {
                throw readFailure(sourcePath, ex);
            }
        }
        if (!directory.toRealPath().equals(directory) || !sameEntry(before, attributes(directory))) {
            throw changed(sourcePath(directory));
        }
    }

    private byte[] capture(Path lexical) throws IOException {
        Path target = lexical.toRealPath();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Policy file must resolve inside the selected source directory.");
        }
        BasicFileAttributes before = attributes(target);
        if (!before.isRegularFile()) {
            throw new IOException("Policy sources must be regular files.");
        }
        byte[] bytes;
        try (var channel = Files.newByteChannel(target, Set.of(READ, NOFOLLOW_LINKS))) {
            bytes = Channels.newInputStream(channel).readAllBytes();
        }
        // Plain catalogs inspect operator-controlled directories. Reject observed edits or retargeted
        // aliases before using the bytes. The read and pathname checks aren't a filesystem transaction.
        if (!lexical.toRealPath().equals(target) || bytes.length != before.size()
                || !sameEntry(before, attributes(target))) {
            throw changed(sourcePath(lexical));
        }
        return bytes;
    }

    private static boolean sameEntry(BasicFileAttributes before, BasicFileAttributes after) {
        return before.isDirectory() == after.isDirectory() && before.isRegularFile() == after.isRegularFile()
            && Objects.equals(before.fileKey(), after.fileKey()) && before.size() == after.size()
            && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private String sourcePath(Path path) {
        return posix(root.getParent().relativize(path));
    }

    private static String posix(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    }

    private static InvalidPolicyDocumentException changed(String path) {
        return new InvalidPolicyDocumentException("Policy source " + path
            + " changed during inspection. Retry with a stable source directory.");
    }

    static InvalidPolicyDocumentException readFailure(String sourcePath, Exception ex) {
        return new InvalidPolicyDocumentException("Could not read policy document " + sourcePath + ".", ex);
    }
}
