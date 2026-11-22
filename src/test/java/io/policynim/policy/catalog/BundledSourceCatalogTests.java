package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundledSourceCatalogTests {

    private static final String ROOT = "/io/policynim/bundled/policies/";
    private static final String MANIFEST = """
        schema_version = 1
        pack_id = "rules"
        version = "1.0.0"
        [[policies]]
        policy_id = "RULE"
        path = "rule.md"
        """;
    private static final String RULE = "---\npolicy_id: RULE\n---\n# Rule\n";
    private static final String RECOVERY = " Reinstall PolicyNIM or pass --corpus-root=<path> to select a local policy directory.";

    @Test
    void preservesTheIndependentPackDigestAndOriginalMember() throws IOException {
        var fixture = new ObjectMapper().readTree(getClass().getResource("/parity/catalog/explicit-logging.json"));
        var resources = Map.of(ROOT + "pack.toml", fixture.get("manifest").textValue(),
            ROOT + "policy.md", fixture.get("member").textValue());
        var catalog = SourceCatalog.readBundled(path -> stream(resources.get(path)));
        var expected = fixture.get("catalog");

        assertThat(catalog.list().get("policies")).isEqualTo(expected.get("policies"));
        assertThat(catalog.validate().get("catalog_digest")).isEqualTo(expected.get("digest"));
        assertThat(catalog.validate().get("unlisted_markdown").isEmpty()).isTrue();
    }

    @Test
    void namesTheUnavailableResourceAndReadsTheCorrectedInstallation() {
        var resources = new HashMap<String, String>();
        assertFailure(resources, "Missing bundled policy resource policies/pack.toml.");
        resources.put(ROOT + "pack.toml", "incomplete = [");
        assertFailure(resources, "Invalid bundled policy resource policies/pack.toml.");
        resources.put(ROOT + "pack.toml", MANIFEST);
        assertFailure(resources, "Missing bundled policy resource policies/rule.md.");
        resources.put(ROOT + "rule.md", RULE.replace("policy_id: RULE", "policy_id: OTHER"));
        assertFailure(resources, "Invalid bundled policy resource policies/rule.md.");

        resources.put(ROOT + "rule.md", RULE);
        assertThat(SourceCatalog.readBundled(path -> stream(resources.get(path))).show("RULE").get("body").textValue())
            .isEqualTo("# Rule\n");
    }

    @Test
    void validatesMemberLocationsBeforeOpeningThem() {
        var resources = new HashMap<String, String>();
        resources.put(ROOT + "pack.toml", MANIFEST.replace("rule.md", "../other.md"));
        assertFailure(resources, "Invalid bundled policy resource policies/pack.toml.");
        resources.put(ROOT + "rule.md", RULE);
        resources.put(ROOT + "pack.toml", MANIFEST + """
            [[policies]]
            policy_id = "OTHER"
            path = "./rule.md"
            """);
        assertFailure(resources, "Invalid bundled policy resource policies/pack.toml.");
    }

    @Test
    void closesTheManifestAndMemberStreamsWhenAMemberReadFails() throws IOException {
        var closed = new ArrayList<String>();
        InputStream unreadable = InputStream.nullInputStream();
        unreadable.close();
        var resources = Map.of(ROOT + "pack.toml", stream(MANIFEST), ROOT + "rule.md", unreadable);
        assertThatThrownBy(() -> SourceCatalog.readBundled(path -> new FilterInputStream(resources.get(path)) {
            @Override
            public void close() throws IOException {
                super.close();
                closed.add(path);
            }
        })).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessage("Couldn't read bundled policy resource policies/rule.md." + RECOVERY);
        assertThat(closed).containsExactly(ROOT + "pack.toml", ROOT + "rule.md");
    }

    private static void assertFailure(Map<String, String> resources, String diagnosis) {
        assertThatThrownBy(() -> SourceCatalog.readBundled(path -> stream(resources.get(path))))
            .isInstanceOf(InvalidPolicyDocumentException.class).hasMessage(diagnosis + RECOVERY);
    }

    private static ByteArrayInputStream stream(String text) {
        return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
