package io.policynim.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.support.PackagedMcpHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.policynim.support.PackagedMcpHttp.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class GroundedHttpIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "/policy-tools";
    private static final String LEGACY = "2025-11-25";
    private static final String MODERN = "2026-07-28";
    private static final Map<String, String> CONFIG = Map.of(
        "POLICYNIM_MCP_CONTRACT", "grounded-v1",
        "POLICYNIM_MCP_PUBLIC_BASE_URL", "https://parity.invalid",
        "SPRING_AI_MCP_SERVER_STREAMABLEHTTP_MCPENDPOINT", ENDPOINT
    );
    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
         "capabilities":{},"clientInfo":{"name":"grounded-http-test","version":"1"}}}""";
    private static final String READY = """
        {"jsonrpc":"2.0","method":"notifications/initialized"}""";
    private static final String LEGACY_SEARCH = """
        {"jsonrpc":"2.0","id":2,"method":"tools/call",
         "params":{"name":"policy_search","arguments":{"query":"service readiness"}}}""";
    private static final String MODERN_SEARCH = """
        {"jsonrpc":"2.0","id":3,"method":"tools/call",
         "params":{"name":"policy_search","arguments":{"query":"service readiness"},"_meta":{
         "io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final Map<String, String> MODERN_HEADERS = Map.of(
        "MCP-Protocol-Version", MODERN, "Mcp-Method", "tools/call", "Mcp-Name", "policy_search"
    );

    @Test
    void legacyClientCallsToolsWithoutASessionAtTheConfiguredEndpoint(@TempDir Path directory) throws Exception {
        String token = UUID.randomUUID().toString();
        var child = PackagedMcpHttp.startServer(directory, CONFIG, token);
        try (var http = HttpClient.newHttpClient()) {
            URI endpoint = PackagedMcpHttp.readBoundOrigin(child).resolve(ENDPOINT);
            var protectedPath = endpoint.resolve(ENDPOINT + "/");
            for (URI path : new URI[]{endpoint, protectedPath}) {
                assertThat(postJson(http, path, INITIALIZE, null, Map.of()).statusCode()).isEqualTo(401);
                assertThat(postJson(http, path, INITIALIZE, "wrong", Map.of()).statusCode()).isEqualTo(401);
            }
            var initialized = postJson(http, endpoint, INITIALIZE, token, Map.of());
            assertThat(PackagedMcpHttp.result(initialized, 1).required("protocolVersion").textValue()).isEqualTo(LEGACY);
            assertJsonWithoutSession(initialized);
            var headers = Map.of("MCP-Protocol-Version", LEGACY);
            assertThat(postJson(http, endpoint, READY, token, headers).statusCode()).isEqualTo(202);
            var missing = postJson(http, endpoint, """
                {"jsonrpc":"2.0","id":"missing-query","method":"tools/call",
                 "params":{"name":"policy_search","arguments":{}}}""", token, headers);
            assertThat(missing.statusCode()).isEqualTo(200);
            JsonNode frame = JSON.readTree(missing.body());
            assertThat(frame.required("jsonrpc").textValue()).isEqualTo("2.0");
            assertThat(frame.required("id").textValue()).isEqualTo("missing-query");
            assertThat(frame.has("error")).isFalse();
            JsonNode refusal = frame.required("result");
            assertThat(refusal.required("isError").booleanValue()).isTrue();
            assertThat(refusal.required("content").required(0).required("text").textValue()).contains("query");
            var called = postJson(http, endpoint, LEGACY_SEARCH, token, headers);
            assertReadinessRefusal(PackagedMcpHttp.result(called, 2));
            assertJsonWithoutSession(called);

            child.destroy();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).as("packaged shutdown").isTrue();
        }
        finally {
            PackagedMcpHttp.reap(child);
        }
    }

    @Test
    void modernClientCanCallBeforeDiscoveryAndRecoverAfterAHeaderDisagreement(@TempDir Path directory)
        throws Exception {
        String token = UUID.randomUUID().toString();
        var child = PackagedMcpHttp.startServer(directory, CONFIG, token);
        try (var http = HttpClient.newHttpClient()) {
            URI endpoint = PackagedMcpHttp.readBoundOrigin(child).resolve(ENDPOINT);
            assertThat(postJson(http, endpoint, MODERN_SEARCH, null, MODERN_HEADERS).statusCode()).isEqualTo(401);
            assertModernCall(http, endpoint, token);

            var disagreement = Map.of("MCP-Protocol-Version", MODERN, "Mcp-Method", "tools/call",
                "Mcp-Name", "policy_preflight");
            var rejected = postJson(http, endpoint, MODERN_SEARCH, token, disagreement);
            assertThat(rejected.statusCode()).isEqualTo(400);
            JsonNode error = JSON.readTree(rejected.body());
            assertThat(error.required("id").intValue()).isEqualTo(3);
            assertThat(error.required("error").required("code").intValue()).isEqualTo(-32020);
            assertModernCall(http, endpoint, token);
        }
        finally {
            PackagedMcpHttp.reap(child);
        }
    }

    @Test
    void shutdownClosesAnOpenGroundedLegacyGetStream(@TempDir Path directory) throws Exception {
        String token = UUID.randomUUID().toString();
        var child = PackagedMcpHttp.startServer(directory, CONFIG, token);
        try (var http = HttpClient.newHttpClient()) {
            URI endpoint = PackagedMcpHttp.readBoundOrigin(child).resolve(ENDPOINT);
            var listen = PackagedMcpHttp.request(endpoint).setHeader("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token).header("MCP-Protocol-Version", LEGACY).GET().build();
            var response = http.send(listen, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
                child.destroy();
                assertThat(child.waitFor(8, TimeUnit.SECONDS)).as("shutdown with an attached GET").isTrue();
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> stream.transferTo(OutputStream.nullOutputStream()));
            }
        }
        finally {
            PackagedMcpHttp.reap(child);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void omittedAndExplicitJavaContractStillRequireTheIssuedSession(boolean explicit, @TempDir Path directory)
        throws Exception {
        String token = UUID.randomUUID().toString();
        var child = PackagedMcpHttp.startServer(directory,
            explicit ? Map.of("POLICYNIM_MCP_CONTRACT", "java-v1") : Map.of(), token);
        try (var http = HttpClient.newHttpClient()) {
            URI endpoint = PackagedMcpHttp.readBoundOrigin(child).resolve("/mcp");
            var dualAccept = Map.of("Accept", "application/json, text/event-stream");
            var initialized = postJson(http, endpoint, INITIALIZE, token, dualAccept);
            assertThat(PackagedMcpHttp.result(initialized, 1).required("protocolVersion").textValue()).isEqualTo(LEGACY);
            var issued = initialized.headers().firstValue("Mcp-Session-Id");
            assertThat(issued).as("java-v1 initialize must issue a session ID").isPresent();
            String id = issued.orElseThrow();
            var legacy = Map.of("Accept", "application/json, text/event-stream", "MCP-Protocol-Version", LEGACY);
            assertThat(postJson(http, endpoint, LEGACY_SEARCH, token, legacy).statusCode()).isEqualTo(400);
            var withSession = Map.of("Accept", "application/json, text/event-stream", "MCP-Protocol-Version", LEGACY,
                "Mcp-Session-Id", id);
            assertThat(postJson(http, endpoint, READY, token, withSession).statusCode()).isEqualTo(202);
            assertReadinessRefusal(PackagedMcpHttp.result(postJson(http, endpoint, LEGACY_SEARCH, token, withSession), 2));
        }
        finally {
            PackagedMcpHttp.reap(child);
        }
    }

    private static void assertModernCall(HttpClient http, URI endpoint, String token) throws Exception {
        var response = postJson(http, endpoint, MODERN_SEARCH, token, MODERN_HEADERS);
        JsonNode result = PackagedMcpHttp.result(response, 3);
        assertThat(result.required("resultType").textValue()).isEqualTo("complete");
        assertReadinessRefusal(result);
        assertJsonWithoutSession(response);
    }

    private static void assertReadinessRefusal(JsonNode result) {
        assertThat(result.required("isError").booleanValue()).isTrue();
        assertThat(result.required("content").required(0).required("text").textValue()).contains("until PolicyNIM is ready");
    }

    private static void assertJsonWithoutSession(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.headers().allValues("Mcp-Session-Id")).isEmpty();
    }
}
