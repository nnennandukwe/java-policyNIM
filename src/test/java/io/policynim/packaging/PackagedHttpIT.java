package io.policynim.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import io.policynim.support.PackagedMcpHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static io.policynim.support.PackagedMcpHttp.INITIALIZE;
import static io.policynim.support.PackagedMcpHttp.INITIALIZED;
import static io.policynim.support.PackagedMcpHttp.VERSION;
import static io.policynim.support.PackagedMcpHttp.post;
import static io.policynim.support.PackagedMcpHttp.request;
import static io.policynim.support.PackagedMcpHttp.result;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedHttpIT {

    private static final String LIST = """
        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""";
    private static final String SEARCH = """
        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"policy_search","arguments":{"query":"status"}}}""";

    @Test
    void authenticatedClientUsesAStatefulSessionWithThePackagedServer(@TempDir Path directory) throws Exception {
        Path jar = Path.of("target", "policynim.jar").toAbsolutePath();
        String token = UUID.randomUUID().toString();
        Process child = PackagedMcpHttp.startServer(directory, Map.of(), token);
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            URI origin = PackagedMcpHttp.readBoundOrigin(child);
            assertEquals(200, http.send(request(origin.resolve("/livez")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
            URI endpoint = origin.resolve("/mcp");
            var unauthorized = post(http, endpoint, INITIALIZE, null, null);
            assertEquals(401, unauthorized.statusCode());
            assertTrue(unauthorized.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));

            var initialize = post(http, endpoint, INITIALIZE, token, null);
            JsonNode identity = result(initialize, 1);
            assertEquals(VERSION, identity.path("protocolVersion").textValue());
            String session = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
            assertFalse(session.isBlank());
            assertAll("Installed legacy HTTP contract",
                () -> assertPackagedVersion(jar, identity),
                () -> exerciseSession(http, endpoint, token, session)
            );
            child.destroy();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "The HTTP process didn't stop after termination.");
        }
        finally {
            PackagedMcpHttp.reap(child);
        }
    }

    private static void assertPackagedVersion(Path jar, JsonNode initialized) throws IOException {
        try (var archive = new JarFile(jar.toFile())) {
            String version = archive.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            assertTrue(version != null && !version.isBlank(), "The jar must declare its implementation version.");
            assertEquals(version, initialized.path("serverInfo").path("version").textValue());
        }
    }

    private static void exerciseSession(HttpClient http, URI endpoint, String token, String session) throws Exception {
        assertEquals(202, post(http, endpoint, INITIALIZED, token, session).statusCode());
        assertEquals(400, post(http, endpoint, LIST, token, null).statusCode());
        assertEquals(404, post(http, endpoint, LIST, token, UUID.randomUUID().toString()).statusCode());
        assertEquals(401, post(http, endpoint, LIST, null, session).statusCode());

        Set<String> names = new HashSet<>();
        result(post(http, endpoint, LIST, token, session), 2).path("tools")
            .forEach(tool -> names.add(tool.path("name").textValue()));
        assertTrue(names.containsAll(Set.of("policy_search", "policy_preflight")), "Missing baseline tools.");
        JsonNode refusal = result(post(http, endpoint, SEARCH, token, session), 3);
        assertTrue(refusal.path("isError").asBoolean(), "An unready index must refuse search.");
        assertTrue(refusal.path("content").path(0).path("text").asText().contains("until PolicyNIM is ready"));

        var close = request(endpoint).header("Authorization", "Bearer " + token)
            .header("Mcp-Session-Id", session).header("MCP-Protocol-Version", VERSION).DELETE().build();
        assertEquals(200, http.send(close, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(404, post(http, endpoint, LIST, token, session).statusCode());
    }

}
