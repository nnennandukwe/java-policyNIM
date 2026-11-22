package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceCatalogTests {
    @TempDir Path temporary;

    @Test
    void publishesTheCatalogTargetAndContentDigestsOfTheSameMarkdown() throws Exception {
        Path root = temporary.resolve("policies");
        Files.createDirectories(root.resolve("api"));
        Files.writeString(root.resolve("api/tasks.md"), "# Tasks\n");

        SourceCatalog catalog = SourceCatalog.read(root);
        ObjectNode view = catalog.show("API-TASKS");

        assertThat(view.at("/policy/provenance/content_digest").textValue())
            .isEqualTo("sha256:8c78633697597050ade157306c7ffe98c2446b6dc1163ee569124ee0296e304c");
        assertThat(view.path("target_digest").textValue())
            .isEqualTo("sha256:e2bcfe20a6a180b224c83d765883ca019d1a3d6f1a138395e95cb572198b7064");
        assertThat(catalog.validate().path("catalog_digest").textValue())
            .isEqualTo("sha256:8c0aed1bd0c8930d5c27140b32d67fe0dda219bbd37cba272fb9d99a13684572");
        assertThat(catalog.list().at("/policies/0/path").textValue()).isEqualTo("api/tasks.md");
    }

    @Test
    void hashesTheCommittedRecoveryPolicy() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("policies"));
        try (var fixture = getClass().getResourceAsStream("/parity/policies/recovery.md")) {
            Files.copy(java.util.Objects.requireNonNull(fixture), root.resolve("recovery.md"));
        }
        SourceCatalog catalog = SourceCatalog.read(root);
        ObjectNode entry = catalog.show("OPS-RECOVERY-001");

        assertThat(entry.at("/policy/provenance/content_digest").textValue())
            .isEqualTo("sha256:5e6fd8379409176c36260af483c1e18b95454b11425066b94fbc628758f115e0");
        assertThat(entry.path("target_digest").textValue())
            .isEqualTo("sha256:60e264cca1b67aa7c8e149dd495ed74e2720254702dadfe215ee745f02e103b6");
        assertThat(catalog.validate().path("catalog_digest").textValue())
            .isEqualTo("sha256:8235dea85dcda12002099e86802cb31ece64c5597ae9f1ca2dc736be3d46b749");
    }

    @Test
    void hashesTheOriginalBomAndCrlfFromTheFileUsedForNormalizedBody() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("policies"));
        String source = "---\npolicy_id: BOM-01\ntitle: Résumé\n---\n# Review\nRead the log.\n";
        Files.writeString(root.resolve("source.md"), "\ufeff" + source.replace("\n", "\r\n"));

        SourceCatalog catalog = SourceCatalog.read(root);
        ObjectNode entry = catalog.show("BOM-01");
        assertThat(entry.path("body").textValue()).isEqualTo("# Review\nRead the log.\n");
        assertThat(entry.at("/policy/provenance/content_digest").textValue())
            .isEqualTo("sha256:800c6bc8ae8b1d169c4f84abc93952f7c9c0b32ed7310d18ef63f78b2c27b282");
        assertThat(entry.path("target_digest").textValue())
            .isEqualTo("sha256:cadbe4772b7a904e74bab99eb555762fa77313a231082b90c314d8f2412abcb4");
        assertThat(catalog.list().path("digest").textValue())
            .isEqualTo("sha256:06b7e2b5fcc311a3caac5ba36edeb2e3db956be6a468b0e2bbe0ca49f8fdd998");
    }

    @Test
    void hashesRawSupplementaryCharactersLowercaseControlEscapesAndCodePointSortedEntries() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("policies"));
        String source = "---\npolicy_id: %s\ntitle: Résumé\ndomain: ops\n---\n"
            + "# Review\nKeep \u001b, quotes \" and slash /, backslash \\ and 😀.\n";
        Files.writeString(root.resolve("first.md"), source.formatted("𐀀"));
        Files.writeString(root.resolve("second.md"), source.formatted("\ue000"));

        SourceCatalog catalog = SourceCatalog.read(root);
        ObjectNode list = catalog.list();
        assertThat(list.at("/policies/0/policy/policy_id").textValue()).isEqualTo("\ue000");
        assertThat(list.at("/policies/1/policy/policy_id").textValue()).isEqualTo("𐀀");
        assertThat(list.at("/policies/0/target_digest").textValue())
            .isEqualTo("sha256:281e9501663925aa6860fc84cd24819b01334d716e1465f80d13d7394d7fe1d6");
        assertThat(list.at("/policies/1/target_digest").textValue())
            .isEqualTo("sha256:74142d0bffea4acdc74471e9856b0a24c08ed7901ba6bcbf214bab9b95152b74");
        assertThat(list.path("digest").textValue())
            .isEqualTo("sha256:98e2e0f863296b64f91d88c115c418029d10770f272274d40a954fddf62bc680");
    }

    @Test
    void usesLexicalFileAliasNamesAndSkipsDirectoryLinksAndExactTemplateNames() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("policies"));
        Path deep = Files.createDirectories(root.resolve("team/time/decisions"));
        Files.writeString(deep.resolve("note.txt"), "# Read\n");
        Files.writeString(deep.resolve("first.md"), "# First\n");
        Files.writeString(deep.resolve("TEMPLATE.md"), "# Template\n");
        Files.createSymbolicLink(root.resolve("alias.md"), root.relativize(deep.resolve("note.txt")));
        Files.createSymbolicLink(root.resolve("dir-link"), root.relativize(deep));

        SourceCatalog catalog = SourceCatalog.read(root);
        assertThat(catalog.validate().path("effective_count").intValue()).isEqualTo(2);
        assertThat(catalog.show("ALIAS").path("path").textValue()).isEqualTo("alias.md");
        assertThat(catalog.show("TEAM-TIME-DECISIONS-FIRST").path("path").textValue())
            .isEqualTo("team/time/decisions/first.md");
    }

    @Test
    void rejectsAFileAliasOutsideTheRootAndAcceptsItAfterCorrection() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("policies"));
        Path sample = Files.writeString(temporary.resolve("sample.txt"), "# Fixture\n");
        Path alias = Files.createSymbolicLink(root.resolve("alias.md"), sample);

        assertUnreadable(root, "alias.md");
        Files.delete(alias);
        Files.move(sample, root.resolve("sample.txt"));
        Files.createSymbolicLink(alias, Path.of("sample.txt"));
        assertThat(SourceCatalog.read(root).show("ALIAS").path("body").textValue()).isEqualTo("# Fixture\n");
    }

    @Test
    void rejectsUnreadableOrInvalidMarkdownAsAnErrorForTheEntireCatalog() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("policies"));
        Files.writeString(root.resolve("first.md"), "# First\n");
        Path member = Files.createDirectory(root.resolve("issue.md"));
        assertUnreadable(root, "issue.md");

        Files.delete(member);
        Files.createSymbolicLink(member, Path.of("missing.txt"));
        assertUnreadable(root, "issue.md");

        Files.delete(member);
        Files.write(member, new byte[] {(byte) 0xff});
        assertUnreadable(root, "issue.md");
        Files.writeString(member, "# Valid\n");
        assertThat(SourceCatalog.read(root).validate().path("effective_count").intValue()).isEqualTo(2);
    }

    @Test
    void duplicateErrorsVisitPathComponentsInScalarOrder() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("policies"));
        Files.writeString(root.resolve("one-two.md"), "# One\n");
        Files.createDirectory(root.resolve("one"));
        Files.writeString(root.resolve("one/two.md"), "# Two\n");

        assertThatThrownBy(() -> SourceCatalog.read(root)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessage("Duplicate effective policy_id 'ONE-TWO' in policies/one/two.md and policies/one-two.md.");
    }

    @Test
    void refusesUnsupportedSelectionsAndKeepsCapturedEntriesPrivate() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("policies"));
        assertThatThrownBy(() -> SourceCatalog.read(root)).hasMessageContaining("no policies");
        Files.writeString(root.resolve("a.md"), "---\npolicy_id: Mixed\n---\n# A\n");
        Path manifest = Files.createSymbolicLink(root.resolve("pack.toml"), Path.of("missing"));
        assertThatThrownBy(() -> SourceCatalog.read(root)).hasMessageContaining("pack.toml");
        Files.delete(manifest);

        SourceCatalog catalog = SourceCatalog.read(root);
        assertThatThrownBy(() -> catalog.show("mixed")).hasMessage(
            "Policy ID 'mixed' isn't in the source catalog. Run `policynim policies list --source`.");
        catalog.show("Mixed").withObject("/policy").put("title", "Changed by caller");
        ((ObjectNode) catalog.list().at("/policies/0")).put("body", "Changed by caller");
        Files.delete(root.resolve("a.md"));
        assertThat(catalog.show("Mixed").path("body").textValue()).isEqualTo("# A\n");
        assertThat(catalog.list().at("/policies/0/policy/title").textValue()).isEqualTo("A");
    }

    private static void assertUnreadable(Path root, String path) {
        assertThatThrownBy(() -> SourceCatalog.read(root)).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessage("Could not read policy document policies/" + path + ".");
    }
}
