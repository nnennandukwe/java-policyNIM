package io.policynim.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.support.PackagedMcpHttp;
import io.policynim.support.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.policynim.support.PackagedMcpHttp.INITIALIZE;
import static io.policynim.support.PackagedMcpHttp.INITIALIZED;
import static io.policynim.support.PackagedMcpHttp.post;
import static io.policynim.support.PackagedMcpHttp.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedJdbcSearchIT {

    private static final String SEARCH = """
        {"jsonrpc":"2.0","id":2,"method":"tools/call",\
        "params":{"name":"policy_search","arguments":{"query":"checkpoint","domain":"reliability"}}}""";

    @Test
    void separateJarReadsIngestedPolicyOverAuthenticatedHttp(@TempDir Path directory) throws Exception {
        Path corpus = Files.createDirectory(directory.resolve("policies"));
        Files.writeString(corpus.resolve("recovery.md"), """
            ---
            policy_id: OPS-RECOVERY-001
            title: Recovery
            domain: reliability
            ---
            # Recovery
            Record a checkpoint before restoring the queue.
            """);
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
        }
    }

    private static void assertStoredPolicy(JsonNode toolResult) throws Exception {
        assertFalse(toolResult.path("isError").asBoolean());
        JsonNode search = toolResult.path("structuredContent");
        assertEquals(search, new ObjectMapper().readTree(toolResult.path("content").path(0).path("text").asText()));
        assertEquals("checkpoint", search.path("query").textValue());
        assertEquals(5, search.path("topK").intValue());
        assertTrue(search.path("insufficientContext").isBoolean());
        assertFalse(search.get("insufficientContext").booleanValue());
        assertEquals(1, search.path("hits").size());
        JsonNode hit = search.get("hits").get(0);
        assertEquals("OPS-RECOVERY-001:recovery", hit.path("chunkId").textValue());
        assertEquals("policies/recovery.md", hit.path("path").textValue());
        assertEquals("6-7", hit.path("lines").textValue());
        assertEquals("OPS-RECOVERY-001", hit.path("policy").path("policyId").textValue());
        assertEquals("# Recovery\nRecord a checkpoint before restoring the queue.", hit.path("text").textValue());
    }
}
