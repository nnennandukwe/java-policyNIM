package io.policynim.policy.catalog;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackDirectoryTests {

    @TempDir
    Path root;

    @Test
    void readsTheSelectedManifestAndRegularMemberBytes() throws Exception {
        Path selected = root.toRealPath();
        byte[] manifest = {49, 10};
        byte[] source = {0, 10, 13, (byte) 0xff};
        Files.write(selected.resolve("pack.toml"), manifest);
        Files.createDirectory(selected.resolve("team"));
        Files.write(selected.resolve("team/member.md"), source);
        try (var directory = PackDirectory.open(selected)) {
            assertArrayEquals(manifest, directory.read(directory.resolve("pack.toml")));
            var member = directory.resolve("team/member.md");
            assertEquals(selected.resolve("team/member.md"), member.identity());
            assertArrayEquals(source, directory.read(member));
        }
    }

    @Test
    void resolvesInRootFileAndDirectoryAliasesAndKeepsHardLinkNamesSeparate() throws Exception {
        Path selected = root.toRealPath();
        Path team = Files.createDirectory(selected.resolve("team"));
        Path target = Files.write(team.resolve("member.md"), new byte[] {42});
        Path alias = symbolicLink(selected.resolve("alias.md"), Path.of("team", "member.md"));
        symbolicLink(selected.resolve("review"), Path.of("team"));
        Files.createLink(selected.resolve("second.md"), target);
        try (var directory = PackDirectory.open(selected)) {
            var direct = directory.resolve("team/member.md");
            var byFile = directory.resolve("alias.md");
            var byDirectory = directory.resolve("review/member.md");
            assertEquals("alias.md", directory.relativeName(alias));
            assertEquals(direct.identity(), byFile.identity());
            assertEquals(direct.identity(), byDirectory.identity());
            assertNotEquals(direct.identity(), directory.resolve("second.md").identity());
            assertArrayEquals(new byte[] {42}, directory.read(byDirectory));
        }
    }

    @Test
    void readsTheManifestSpellingOfAnAccentedNameAndPreservesTheLiteralPercent() throws Exception {
        Path selected = root.toRealPath();
        Path member = Path.of(selected.toUri().resolve("caf%C3%A9%25.md"));
        Files.write(member, new byte[] {23});
        try (var directory = PackDirectory.open(selected)) {
            assertEquals("caf\u00e9%.md", directory.relativeName(member));
            assertArrayEquals(new byte[] {23}, directory.read(directory.resolve("caf\u00e9%.md")));
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void preservesOrdinaryComponentSpellingInMacAliasIdentity() throws Exception {
        Path selected = root.toRealPath();
        Path composed = Path.of(selected.toUri().resolve("caf%C3%A9.md"));
        Path decomposed = Path.of(selected.toUri().resolve("cafe%CC%81.md"));
        Files.write(composed, new byte[] {17});
        // Some Mac volumes preserve both names. A normalization-insensitive volume only needs one file.
        if (!Files.exists(decomposed)) {
            Files.write(decomposed, new byte[] {17});
        }
        try (var directory = PackDirectory.open(selected)) {
            var first = directory.resolve("caf\u00e9.md");
            var second = directory.resolve("cafe\u0301.md");
            assertNotEquals(first.identity(), second.identity());
            assertArrayEquals(new byte[] {17}, directory.read(second));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsDirectoryNamesWhoseNativeBytesArentUtf8() throws Exception {
        Path selected = root.toRealPath();
        // URI.resolve would produce file:/ and the Unix JDK would replace FF with UTF-8 for U+FFFD.
        URI raw = URI.create(selected.toUri().toASCIIString() + "bad%FF.md");
        Files.createFile(Path.of(raw));
        try (var entries = Files.list(selected); var directory = PackDirectory.open(selected)) {
            Path invalid = entries.findFirst().orElseThrow();
            assertEquals(raw.getRawPath(), invalid.toUri().getRawPath());
            assertThrows(CharacterCodingException.class, () -> directory.relativeName(invalid));
        }
    }

    @Test
    void refusesInvalidMembershipAndReadsACorrectedFile() throws Exception {
        Path selected = Files.createDirectory(root.resolve("pack")).toRealPath();
        Files.createDirectory(selected.resolve("directory.md"));
        try (var directory = PackDirectory.open(selected)) {
            for (String name : List.of("../other.md", "/absolute.md", "missing.md")) {
                assertThrows(IOException.class, () -> directory.resolve(name), name);
            }
            assertThrows(PackDirectory.NotRegularFileException.class, () -> directory.resolve("directory.md"));
            Files.write(selected.resolve("missing.md"), new byte[] {9});
            assertArrayEquals(new byte[] {9}, directory.read(directory.resolve("missing.md")));
        }
    }

    @Test
    void distinguishesAnOutsideAliasFromAMissingFileInsideThePack() throws Exception {
        Path selected = Files.createDirectory(root.resolve("pack")).toRealPath();
        Files.write(root.resolve("other.md"), new byte[] {7});
        symbolicLink(selected.resolve("outside.md"), Path.of("..", "other.md"));
        symbolicLink(selected.resolve("absent-outside.md"), Path.of("..", "absent.md"));
        symbolicLink(selected.resolve("absent-inside.md"), Path.of("absent.md"));
        try (var directory = PackDirectory.open(selected)) {
            assertThrows(PackDirectory.OutsideRootException.class, () -> directory.resolve("outside.md"));
            assertThrows(PackDirectory.OutsideRootException.class, () -> directory.resolve("absent-outside.md"));
            assertThrows(java.nio.file.NoSuchFileException.class, () -> directory.resolve("absent-inside.md"));
        }
    }

    private static Path symbolicLink(Path path, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(path, target);
        } catch (FileSystemException ex) {
            boolean missingPrivilege = ex instanceof AccessDeniedException
                || String.valueOf(ex.getReason()).toLowerCase(Locale.ROOT).contains("privilege");
            Assumptions.assumeFalse(path.getFileSystem().getSeparator().equals("\\") && missingPrivilege,
                "Creating a symbolic link requires a Windows privilege.");
            throw ex;
        }
    }

    @Test
    void tiesResolvedMembersToTheirOpenDirectory() throws Exception {
        Path selected = root.toRealPath();
        Files.write(selected.resolve("member.md"), new byte[] {1});
        PackDirectory first = PackDirectory.open(selected);
        PackDirectory.ResolvedFile member;
        try (first; var second = PackDirectory.open(selected)) {
            member = first.resolve("member.md");
            assertThrows(IOException.class, () -> second.read(member));
        }
        assertThrows(IOException.class, () -> first.read(member));
        first.close();
        Files.delete(selected.resolve("member.md"));
    }

    @Test
    void aDirectoryReplacedBetweenCommandsNeedsAFreshReader() throws Exception {
        Path selected = Files.createDirectory(root.resolve("pack")).toRealPath();
        Files.write(selected.resolve("member.md"), new byte[] {1});
        try (var original = PackDirectory.open(selected)) {
            var oldMember = original.resolve("member.md");
            Files.move(selected, root.resolve("archived"));
            Files.createDirectory(selected);
            Files.write(selected.resolve("member.md"), new byte[] {2});
            assertThrows(IOException.class, () -> original.read(oldMember));
        }
        try (var current = PackDirectory.open(selected)) {
            assertArrayEquals(new byte[] {2}, current.read(current.resolve("member.md")));
        }
    }
}
