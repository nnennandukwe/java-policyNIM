package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackSourceFilesTests {
    private static final String MANIFEST = """
        schema_version = 1
        pack_id = "public"
        version = "1.0.0"
        [[policies]]
        policy_id = "POL-1"
        path = "policy.md"
        """;

    @TempDir
    Path workspace;

    @Test
    void rejectsEditedOrReplacedManifestAndReadsTheCorrectedVersionOnRetry() throws IOException {
        Path root = pack();
        Path manifest = root.resolve("pack.toml");
        for (boolean replace : List.of(false, true)) {
            Files.writeString(manifest, MANIFEST);
            assertThatThrownBy(() -> PackSourceFiles.read(root, (path, source, bytes, pack, member) -> {
                try {
                    if (replace) {
                        Path next = Files.writeString(root.resolve("next.toml"), MANIFEST.replace("1.0.0", "2.0.0"));
                        Files.move(next, manifest, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.writeString(manifest, MANIFEST.replace("1.0.0", "2.0.0"));
                    }
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            })).isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("pack.toml")
                .hasMessageContaining("changed").hasMessageContaining("Retry");
            assertThat(versions(root)).containsExactly("2.0.0");
        }
    }

    @Test
    void reportsRemovedManifestBeforeReturningAndAllowsRecovery() throws IOException {
        Path root = pack();
        Path manifest = root.resolve("pack.toml");
        assertThatThrownBy(() -> PackSourceFiles.read(root, (path, source, bytes, pack, member) -> {
            try {
                Files.delete(manifest);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        })).isInstanceOf(InvalidPolicyDocumentException.class).hasMessageContaining("pack.toml")
            .hasMessageContaining("Retry");
        Files.writeString(manifest, MANIFEST);
        assertThat(versions(root)).containsExactly("1.0.0");
    }

    @Test
    void distinguishesMovedRootFromAnUnreadableMemberAndCanRetryBoth() throws IOException {
        Path root = pack();
        Path renamed = workspace.resolve("renamed-private-workspace");
        assertThatThrownBy(() -> PackSourceFiles.read(root, (path, source, bytes, pack, member) -> {
            try {
                Files.move(root, renamed);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        })).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("pack directory changed").hasMessageContaining("Retry")
            .hasMessageNotContaining(workspace.toString()).hasMessageNotContaining("renamed-private-workspace");
        Files.move(renamed, root);
        Files.writeString(root.resolve("pack.toml"), MANIFEST + """
            [[policies]]
            policy_id = "POL-2"
            path = "second.md"
            """);
        Path second = root.resolve("second.md");
        Files.writeString(second, "# Second");
        assertThatThrownBy(() -> PackSourceFiles.read(root, (path, source, bytes, pack, member) -> {
            if (path.equals("policy.md")) {
                try {
                    Files.delete(second);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            }
        })).isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("policies/second.md").hasMessageContaining("Check file access")
            .hasMessageNotContaining(workspace.toString());
        Files.writeString(second, "# Second");
        assertThat(versions(root)).containsExactly("1.0.0", "1.0.0");
    }

    private Path pack() throws IOException {
        Path root = Files.createDirectory(workspace.resolve("policies")).toRealPath();
        Files.writeString(root.resolve("pack.toml"), MANIFEST);
        Files.writeString(root.resolve("policy.md"), "# Policy\n");
        return root;
    }

    private static List<String> versions(Path root) throws IOException {
        var versions = new ArrayList<String>();
        PackSourceFiles.read(root, (path, source, bytes, pack, member) -> versions.add(pack.version()));
        return versions;
    }
}
