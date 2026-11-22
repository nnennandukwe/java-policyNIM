package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
