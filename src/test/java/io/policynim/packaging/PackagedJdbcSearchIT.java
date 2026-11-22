package io.policynim.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.support.PackagedMcpHttp;
import io.policynim.support.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.policynim.support.PackagedMcpHttp.INITIALIZE;
import static io.policynim.support.PackagedMcpHttp.INITIALIZED;
import static io.policynim.support.PackagedMcpHttp.post;
import static io.policynim.support.PackagedMcpHttp.postJson;
import static io.policynim.support.PackagedMcpHttp.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedJdbcSearchIT {

    private static final String SEARCH = """
        {"jsonrpc":"2.0","id":2,"method":"tools/call",\
        "params":{"name":"policy_search","arguments":{"query":"checkpoint","domain":"reliability"}}}""";
    private static final String GROUNDED_INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
        "capabilities":{},"clientInfo":{"name":"jdbc-search","version":"1"}}}""";
    private static final String MODERN_SEARCH = """
        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"policy_search",
        "arguments":{"query":"checkpoint","domain":"reliability"},"_meta":{
        "io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";

    @Test
    void separateJarsReadIngestedPolicyOverAuthenticatedHttpAndStdio(@TempDir Path directory) throws Exception {
        Path corpus = Files.createDirectory(directory.resolve("policies"));
        try (var fixture = new ClassPathResource("parity/policies/recovery.md").getInputStream()) {
            Files.copy(fixture, corpus.resolve("recovery.md"));
        }
        try (var postgres = PostgresTestContainerConfiguration.postgresContainer()) {
            postgres.start();
            var database = Map.of(
                "POLICYNIM_STORAGE_MODE", "jdbc",
                "SPRING_DATASOURCE_URL", postgres.getJdbcUrl(),
                "SPRING_DATASOURCE_USERNAME", postgres.getUsername(),
                "SPRING_DATASOURCE_PASSWORD", postgres.getPassword()
            );
            var ingest = PackagedMcpHttp.jarCommand(directory, database, "ingest", "--corpus-root=" + corpus)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                assertTrue(ingest.waitFor(45, TimeUnit.SECONDS), "The packaged ingest command timed out.");
                assertEquals(0, ingest.exitValue(), "The packaged ingest command failed.");
            }
            finally {
                PackagedMcpHttp.reap(ingest);
            }

            Path readerDirectory = Files.createDirectory(directory.resolve("reader"));
            String token = UUID.randomUUID().toString();
            var server = PackagedMcpHttp.startServer(readerDirectory, database, token);
            try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                URI endpoint = PackagedMcpHttp.readBoundOrigin(server).resolve("/mcp");
                var initialize = post(http, endpoint, INITIALIZE, token, null);
                assertEquals(PackagedMcpHttp.VERSION, result(initialize, 1).path("protocolVersion").textValue());
                String session = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
                assertEquals(202, post(http, endpoint, INITIALIZED, token, session).statusCode());
                assertStoredPolicy(result(post(http, endpoint, SEARCH, token, session), 2));
                server.destroy();
                assertTrue(server.waitFor(10, TimeUnit.SECONDS), "The JDBC server didn't stop after termination.");
            }
            finally {
                PackagedMcpHttp.reap(server);
            }
            verifyGroundedReaders(readerDirectory, database, token);
            verifyGroundedStdioReaders(readerDirectory, database);
        }
    }

    private static void verifyGroundedStdioReaders(Path directory, Map<String, String> database) throws Exception {
        var json = new ObjectMapper();
        var modern = PackagedMcpStdio.exchange(directory, database, List.of(json.readTree(MODERN_SEARCH).toString()));
        assertEquals(1, modern.size());
        var result = modern.get(3).required("result");
        assertEquals("complete", result.required("resultType").asText());
        assertStoredPolicy(result);

        var legacy = PackagedMcpStdio.exchange(directory, database, List.of(
            json.readTree(GROUNDED_INITIALIZE).toString(), INITIALIZED, SEARCH));
        assertEquals(2, legacy.size());
        assertEquals("2025-11-25", legacy.get(1).required("result").required("protocolVersion").asText());
        var search = legacy.get(2).required("result");
        assertFalse(search.has("resultType"));
        assertStoredPolicy(search);
    }

    private static void verifyGroundedReaders(Path directory, Map<String, String> database, String token) throws Exception {
        var environment = new HashMap<>(database);
        environment.put("POLICYNIM_MCP_CONTRACT", "grounded-v1");
        environment.put("POLICYNIM_MCP_PUBLIC_BASE_URL", "https://parity.invalid");
        Process server = PackagedMcpHttp.startServer(directory, environment, token);
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            URI endpoint = PackagedMcpHttp.readBoundOrigin(server).resolve("/mcp");
            var modern = postJson(http, endpoint, MODERN_SEARCH, token, Map.of(
                "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", "policy_search"));
            var completed = result(modern, 3);
            assertEquals("complete", completed.required("resultType").textValue());
            assertTrue(modern.headers().allValues("Mcp-Session-Id").isEmpty());
            assertStoredPolicy(completed);

            var initialized = postJson(http, endpoint, GROUNDED_INITIALIZE, token, Map.of());
            assertEquals("2025-11-25", result(initialized, 1).required("protocolVersion").textValue());
            assertTrue(initialized.headers().allValues("Mcp-Session-Id").isEmpty());
            var headers = Map.of("MCP-Protocol-Version", "2025-11-25");
            assertEquals(202, postJson(http, endpoint, INITIALIZED, token, headers).statusCode());
            var legacy = postJson(http, endpoint, SEARCH, token, headers);
            var search = result(legacy, 2);
            assertFalse(search.has("resultType"));
            assertTrue(legacy.headers().allValues("Mcp-Session-Id").isEmpty());
            assertStoredPolicy(search);

            server.destroy();
            assertTrue(server.waitFor(10, TimeUnit.SECONDS), "The grounded JDBC server didn't stop after termination.");
        }
        finally {
            PackagedMcpHttp.reap(server);
        }
    }

    private static void assertStoredPolicy(JsonNode toolResult) throws Exception {
        assertTrue(toolResult.required("isError").isBoolean());
        assertFalse(toolResult.get("isError").booleanValue());
        JsonNode search = toolResult.path("structuredContent");
        assertEquals(search, new ObjectMapper().readTree(toolResult.path("content").path(0).path("text").asText()));
        assertEquals("checkpoint", search.path("query").textValue());
        assertEquals("reliability", search.required("domain").textValue());
        assertEquals(5, search.path("topK").intValue());
        assertTrue(search.path("insufficientContext").isBoolean());
        assertFalse(search.get("insufficientContext").booleanValue());
        assertEquals(1, search.path("hits").size());
        JsonNode hit = search.get("hits").get(0);
        assertEquals("OPS-RECOVERY-001:recovery", hit.path("chunkId").textValue());
        assertEquals("policies/recovery.md", hit.path("path").textValue());
        assertEquals("Recovery", hit.required("section").textValue());
        assertEquals("6-7", hit.path("lines").textValue());
        assertEquals("OPS-RECOVERY-001", hit.path("policy").path("policyId").textValue());
        assertEquals("Recovery", hit.required("policy").required("title").textValue());
        assertEquals("reliability", hit.required("policy").required("domain").textValue());
        assertEquals("# Recovery\nRecord a checkpoint before restoring the queue.", hit.path("text").textValue());
    }
}
