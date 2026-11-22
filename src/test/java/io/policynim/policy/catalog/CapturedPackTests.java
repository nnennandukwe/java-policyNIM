package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapturedPackTests {

    @TempDir
    Path root;

    @Test
    void capturesStandaloneIdentityAndAuthoredRuleSpansFromTheSameBytes() throws Exception {
        var fixture = new ObjectMapper().readTree(getClass().getResource("/parity/catalog/explicit-logging.json"));
        var rules = fixture.get("runtime_rule");
        var location = PackLocation.resolve(root.resolve("profile.toml"), ".");
        assertThatThrownBy(() -> SourceCatalog.capturePack(location))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("require pack.toml");

        Files.writeString(root.resolve("pack.toml"), fixture.get("manifest").textValue());
        Files.writeString(root.resolve("policy.md"), fixture.get("member").textValue()
            .replace("policy_id:", rules.get("frontmatter").textValue() + "policy_id:"));
        var pack = SourceCatalog.capturePack(location);
        var member = pack.members().getFirst();
        assertThat(pack.packId()).isEqualTo("team.logging");
        assertThat(pack.version()).isEqualTo("1.0.0");
        assertThat(member.policyId()).isEqualTo("LOG-001");
        assertThat(member.relativePath()).isEqualTo("policy.md");
        assertThat(member.standaloneEntry().at("/policy/provenance/content_digest")).isEqualTo(rules.get("source_digest"));
        assertThat(member.standaloneEntry().get("target_digest")).isEqualTo(rules.get("target_digest"));
        assertThat(member.revisionDigest()).isEqualTo(rules.get("catalog_digest").textValue());
        assertThat(pack.digest()).isEqualTo(member.revisionDigest());
        var rule = member.source().rules().getFirst();
        assertThat(rule.action()).isEqualTo("file_write");
        assertThat(rule.pathGlobs()).containsExactly("docs/**");
        assertThat(rule.startLine()).isEqualTo(3);
        assertThat(rule.endLine()).isEqualTo(6);
        assertThat(member.source().document().bodyStartLine()).isEqualTo(11);
        member.standaloneEntry().put("path", "team.logging/policy.md");
        assertThat(member.revisionDigest()).isEqualTo(rules.get("catalog_digest").textValue());
        assertThat(member.standaloneEntry().get("path").textValue()).isEqualTo("policy.md");
    }

    @Test
    void capturesTheInstalledTopicUsingItsOwnManifestAndRelativePaths() {
        var pack = SourceCatalog.capturePack(PackLocation.resolve(root.resolve("any.toml"), "builtin:api"));
        assertThat(pack.packId()).isEqualTo("policynim.api");
        assertThat(pack.digest()).isEqualTo("sha256:65a49f9d8211acedeffa188f57c002fff857089e52ecbbbee38f8438e09dc35c");
        assertThat(pack.members()).extracting(CapturedPack.Member::policyId)
            .containsExactly("API-ERROR-001", "API-PAGE-001", "ARCH-API-001");
        var first = pack.members().getFirst();
        assertThat(first.relativePath()).isEqualTo("http-errors.md");
        assertThat(first.source().document().sourcePath()).isEqualTo("api/http-errors.md");
        assertThat(first.revisionDigest()).isEqualTo("sha256:134604f4934c4662656e98e488b4ea0607fea7e6ccea2db2f0197fdaa35c8b5a");
    }

    @Test
    void resolvesLocalAliasesToOneDirectoryAndRejectsUnsupportedSelectorsBeforeAccess() throws Exception {
        var profile = root.resolve("profile.toml");
        var pack = Files.createDirectory(root.resolve("selected"));
        assertThat(PackLocation.resolve(profile, "selected/../selected"))
            .isEqualTo(PackLocation.resolve(profile, pack.toString()));
        assertThat(PackLocation.resolve(profile, "builtin:supply-chain")).isEqualTo(PackLocation.Bundled.SUPPLY_CHAIN);
        for (String invalid : new String[]{"builtin:unknown", "//host/share", "ftp://host/path", "a\u0000b"}) {
            assertThatThrownBy(() -> PackLocation.normalize(invalid))
                .isInstanceOf(InvalidPolicyDocumentException.class).hasMessageNotContaining(invalid);
        }
    }
}
