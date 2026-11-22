package io.policynim.packaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.support.PackagedMcpHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedSourceCatalogIT {

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Test
    void sourceInspectionUsesTheSameBytesFromAnUnrelatedWorkingDirectory(@TempDir Path directory) throws Exception {
        var data = fixture();
        var files = data.required("files");
        Path root = directory.resolve("selected corpus/policies");
        Path simple = put(root, "api/tasks.md", files.required("api/tasks.md").asText());
        var expected = data.required("catalog");
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var cli = new CatalogJar(directory, occupied.getLocalPort());
            String selector = "--corpus-root=" + root;
            var single = cli.run("policies", "validate", "--source", selector, "--format", "json").json(0);
            assertEquals(data.required("single_task_catalog_digest"), single.required("catalog_digest"));
            assertEquals(1, single.required("effective_count").intValue());
            for (var input : files.properties()) {
                put(root, input.getKey(), input.getValue().textValue());
            }
            var listed = cli.run("policies", "list", "--format=json", "--source", "--corpus-root", root.toString()).json(0);
            assertEquals(expected, listed);
            JsonNode rich = expected.required("policies").get(1);
            ObjectNode shown = rich.deepCopy();
            shown.put("scope", "source").putNull("generation_id");
            assertEquals(shown, cli.run("policies", "show", "OPS-\u00c9", "--source", selector, "--format", "json").json(0));

            Files.writeString(simple, "# Tasks\r\n", UTF_8);
            var changed = cli.run("policies", "list", "--source", selector, "--format", "json").json(0);
            JsonNode crlf = data.required("crlf_task");
            assertEquals(crlf.required("catalog_digest"), changed.required("digest"));
            JsonNode task = changed.required("policies").get(0);
            assertEquals(crlf.required("content_digest"), task.required("policy").required("provenance").required("content_digest"));
            assertEquals(crlf.required("target_digest"), task.required("target_digest"));
            assertEquals(crlf.required("body"), task.required("body"));
            // A JDBC connection or an HTTP listener would use this already occupied, test-owned port.
            occupied.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class, () -> {
                try (var connection = occupied.accept()) {
                    throw new AssertionError("Source inspection opened a database connection.");
                }
            });
        }
    }

    @Test
    void alternateRootKeepsItsInferredIdAndUtf8OutputInTheCLocale(@TempDir Path directory) throws Exception {
        Path root = directory.resolve("guidelines");
        String title = "Caf\u00e9 \u6771\u4eac";
        String body = "# " + title + "\n";
        put(root, "api/tasks.md", body);
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var cli = new CatalogJar(directory, occupied.getLocalPort());
            String selector = "--corpus-root=" + root;
            String id = "GUIDELINES-API-TASKS";
            var locale = Map.of("LC_ALL", "C");
            var plain = cli.run(locale, "policies", "show", id, "--source", selector);
            assertEquals(0, plain.exit(), plain::diagnostics);
            assertTrue(plain.stderr().isEmpty(), plain::diagnostics);
            assertTrue(plain.stdout().contains(body), plain::diagnostics);
            var response = cli.run(locale, "policies", "show", id, "--source", selector, "--format", "json");
            assertTrue(response.stdout().contains(title), response::diagnostics);
            var shown = response.json(0);
            assertEquals(id, shown.required("policy").required("policy_id").textValue());
            assertEquals("general", shown.required("policy").required("domain").textValue());
            assertEquals("api/tasks.md", shown.required("path").textValue());
            assertEquals(body, shown.required("body").textValue());
        }
    }

    @Test
    void invalidAndUnsupportedSourcesReturnOnlyAnErrorAndCanBeCorrected(@TempDir Path directory) throws Exception {
        var data = fixture();
        Path root = directory.resolve("policies");
        put(root, "api/tasks.md", "# Tasks\n");
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var cli = new CatalogJar(directory, occupied.getLocalPort());
            String selector = "--corpus-root=" + root;
            var absent = cli.run("policies", "show", "api-tasks", "--source", selector, "--format", "json").sourceError();
            assertTrue(absent.contains("api-tasks") && absent.contains("policies list") && absent.contains("--source"));
            Path bad = Files.createDirectory(root.resolve("directory.md"));
            String failure = cli.run("policies", "validate", "--source", selector, "--format", "json").sourceError();
            assertTrue(failure.contains("directory.md"));
            Files.delete(bad);
            Path manifest = Files.writeString(root.resolve("pack.toml"), "schema_version = 1\n", UTF_8);
            assertTrue(cli.run("policies", "list", "--source", selector, "--format", "json").sourceError().contains("pack.toml"));
            Files.delete(manifest);
            Path outside = Files.writeString(directory.resolve("sibling.md"), "# Fixture\n", UTF_8);
            Path link = Files.createSymbolicLink(root.resolve("linked.md"), outside);
            assertTrue(cli.run("policies", "validate", "--source", selector, "--format", "json").sourceError().contains("linked.md"));
            Files.delete(link);
            assertEquals(data.required("single_task_catalog_digest"),
                cli.run("policies", "validate", "--source", selector, "--format", "json").json(0).required("catalog_digest"));
        }
    }

    @Test
    void helpAndInvalidArgumentsExitBeforeStartingTheServer(@TempDir Path directory) throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var cli = new CatalogJar(directory, occupied.getLocalPort());
            var help = cli.run("policies", "show", "--help");
            assertEquals(0, help.exit(), help::diagnostics);
            assertTrue(help.stdout().contains("--source") && help.stdout().contains("--corpus-root")
                && help.stdout().contains("--format"), help::diagnostics);
            String selector = "--corpus-root=" + directory.resolve("policies");
            var misplaced = cli.run("--spring.main.banner-mode=off", "policies", "validate", "--source", selector,
                "--format", "json");
            assertEquals(2, misplaced.exit(), misplaced::diagnostics);
            misplaced.assertUsageError();
            cli.run("policies", "validate", selector).assertUsageError();
            cli.run("policies", "list", "--source").assertUsageError();
            cli.run("policies", "show", "--source", selector).assertUsageError();
            cli.run("policies", "list", "--source", selector, "--format", "xml").assertUsageError();
        }
    }

    private static JsonNode fixture() throws IOException {
        try (var input = Objects.requireNonNull(PackagedSourceCatalogIT.class.getResourceAsStream("/parity/catalog/plain-source.json"))) {
            return JSON.readTree(input);
        }
    }

    private static Path put(Path root, String relative, String contents) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, contents, UTF_8);
    }

    private record CatalogJar(Path directory, int port) {

        Reply run(String... arguments) throws Exception {
            return run(Map.of("LC_ALL", "C.UTF-8"), arguments);
        }

        Reply run(Map<String, String> overrides, String... arguments) throws Exception {
            Path home = Files.createDirectories(directory.resolve("home"));
            Path cwd = Files.createDirectories(directory.resolve("work"));
            var env = Map.of(
                "HOME", home.toString(), "XDG_DATA_HOME", home.resolve("data").toString(),
                "XDG_CONFIG_HOME", home.resolve("config").toString(),
                "POLICYNIM_MCP_TRANSPORT", "stdio", "POLICYNIM_STORAGE_MODE", "jdbc",
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:" + port + "/source_test?connectTimeout=1",
                "SERVER_ADDRESS", "127.0.0.1", "SERVER_PORT", Integer.toString(port));
            Path stdout = cwd.resolve("stdout");
            Path stderr = cwd.resolve("stderr");
            var launch = PackagedMcpHttp.jarCommand(home, env, arguments);
            launch.environment().putAll(overrides);
            var child = launch.directory(cwd.toFile())
                .redirectErrorStream(false).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            try {
                assertTrue(child.waitFor(15, TimeUnit.SECONDS), "The catalog command didn't exit within 15 seconds.");
                String invocation = "LC_ALL=" + launch.environment().get("LC_ALL") + " " + String.join(" ", arguments);
                return new Reply(invocation, child.exitValue(), Files.readString(stdout), Files.readString(stderr));
            }
            finally {
                PackagedMcpHttp.reap(child);
            }
        }
    }

    private record Reply(String invocation, int exit, String stdout, String stderr) {

        String diagnostics() {
            return "%s exited %d%nstdout: %.1000s%nstderr: %.1000s".formatted(invocation, exit, stdout, stderr);
        }

        void assertUsageError() {
            assertNotEquals(0, exit, this::diagnostics);
            assertTrue(stdout.isBlank(), this::diagnostics);
            assertTrue(stderr.contains("Usage:") && stderr.contains("policies"), this::diagnostics);
        }

        JsonNode json(int expectedExit) throws IOException {
            assertEquals(expectedExit, exit, this::diagnostics);
            assertTrue(stderr.isBlank(), this::diagnostics);
            return JSON.readTree(stdout);
        }

        String sourceError() throws IOException {
            JsonNode error = json(1);
            assertEquals("error", error.required("status").textValue());
            assertEquals("source", error.required("scope").textValue());
            assertTrue(error.required("generation_id").isNull());
            assertEquals(4, error.size());
            return error.required("message").textValue();
        }
    }
}
