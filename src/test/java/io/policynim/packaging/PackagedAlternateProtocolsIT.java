package io.policynim.packaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.policynim.support.PackagedMcpHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.policynim.support.PackagedMcpHttp.INITIALIZE;
import static io.policynim.support.PackagedMcpHttp.post;
import static io.policynim.support.PackagedMcpHttp.request;
import static io.policynim.support.PackagedMcpHttp.result;
import static org.assertj.core.api.Assertions.assertThat;

class PackagedAlternateProtocolsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sseEndsTheOpenEventStreamDuringGracefulShutdown(@TempDir Path directory) throws Exception {
        String token = UUID.randomUUID().toString();
        Process process = PackagedMcpHttp.startServer(directory, Map.of("SPRING_AI_MCP_SERVER_PROTOCOL", "SSE"), token);
        try (var http = HttpClient.newHttpClient()) {
            var origin = PackagedMcpHttp.readBoundOrigin(process);
            var stream = http.send(request(origin.resolve("/sse")).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var events = stream.body()) {
                assertThat(stream.statusCode()).isEqualTo(200);
                process.destroy();
                assertThat(process.waitFor(8, TimeUnit.SECONDS)).as("SSE shutdown with a connected client").isTrue();
                assertThat(new String(events.readAllBytes(), StandardCharsets.UTF_8)).contains("/mcp/message?sessionId=");
            }
        }
        finally {
            PackagedMcpHttp.reap(process);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sseServesTheJavaToolsThroughTheAllowedEndpoints(boolean requireToken, @TempDir Path directory)
        throws Exception {
        String token = UUID.randomUUID().toString();
        String ssePath = requireToken ? "/sse" : "/test-mcp/events";
        var launch = PackagedMcpHttp.jarCommand(directory, Map.of(), "--server.address=127.0.0.1", "--server.port=0",
            "--spring.ai.mcp.server.protocol=SSE");
        if (requireToken) {
            launch.environment().put("POLICYNIM_MCP_AUTH_ENABLED", "true");
            launch.environment().put("POLICYNIM_MCP_BEARER_TOKEN", token);
        }
        else {
            launch.command().add("--spring.ai.mcp.server.streamable-http.mcp-endpoint=/test-mcp");
            launch.command().add("--spring.ai.mcp.server.sse-endpoint=" + ssePath);
            launch.command().add("--spring.ai.mcp.server.sse-message-endpoint=/test-mcp/messages");
        }

        Process process = launch.start();
        try (var http = HttpClient.newHttpClient()) {
            var origin = PackagedMcpHttp.readBoundOrigin(process);
            var denied = http.send(request(origin.resolve("/sse")).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(denied.statusCode()).isEqualTo(requireToken ? 401 : 403);
            var messages = origin.resolve(requireToken ? "/mcp/message" : "/test-mcp/messages");
            String bearer = requireToken ? token : null;
            for (var route : Map.of(origin.resolve(ssePath), "GET", messages, "POST").entrySet()) {
                var head = request(route.getKey()).method("HEAD", HttpRequest.BodyPublishers.noBody());
                if (requireToken) {
                    assertThat(http.send(head.build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
                    head.header("Authorization", "Bearer " + token);
                }
                var unsupported = http.send(head.build(), HttpResponse.BodyHandlers.discarding());
                assertThat(unsupported.statusCode()).isEqualTo(405);
                assertThat(unsupported.headers().firstValue("Allow")).contains(route.getValue());
            }
            assertTransportError(post(http, messages, INITIALIZE, bearer, null), 400, -32601);
            assertTransportError(post(http, messages.resolve(messages.getPath() + "?sessionId=" + UUID.randomUUID()),
                INITIALIZE, bearer, null), 404, -32603);

            var builder = HttpClientSseClientTransport.builder(origin.toString()).sseEndpoint(ssePath);
            if (requireToken) {
                builder.requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token));
            }
            try (var client = McpClient.sync(builder.build()).requestTimeout(Duration.ofSeconds(5)).build()) {
                assertThat(client.initialize().protocolVersion()).isEqualTo("2024-11-05");
                assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactly("policy_search", "policy_preflight");
                var search = client.callTool(McpSchema.CallToolRequest.builder("policy_search").arguments(Map.of()).build());
                assertThat(search.isError()).isTrue();
                assertThat(search.structuredContent()).isEqualTo(Map.of("error", "query must not be blank"));
            }
        }
        finally {
            PackagedMcpHttp.reap(process);
        }
    }

    @Test
    void statelessKeepsItsOwnEmptyRegistryAndAcceptsCallsWithoutASession(@TempDir Path directory) throws Exception {
        String token = UUID.randomUUID().toString();
        Process process = PackagedMcpHttp.startServer(directory, Map.of("SPRING_AI_MCP_SERVER_PROTOCOL", "STATELESS",
            "SPRING_AI_MCP_SERVER_STREAMABLEHTTP_MCPENDPOINT", "/stateless"), token);
        try (var http = HttpClient.newHttpClient()) {
            var endpoint = PackagedMcpHttp.readBoundOrigin(process).resolve("/stateless");
            assertThat(post(http, endpoint, INITIALIZE, null, null).statusCode()).isEqualTo(401);
            var init = post(http, endpoint, INITIALIZE, token, null);
            assertThat(result(init, 1).path("protocolVersion").asText()).isEqualTo("2025-06-18");
            assertThat(init.headers().firstValue("Mcp-Session-Id")).isEmpty();

            var list = post(http, endpoint, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, token, null);
            var tools = result(list, 2).required("tools");
            assertThat(tools.isArray()).isTrue();
            assertThat(tools.size()).isZero();

            var unknown = post(http, endpoint, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"policy_search","arguments":{}}}
                """, token, null);
            var rpc = JSON.readTree(unknown.body());
            assertThat(rpc.path("id").asInt()).isEqualTo(3);
            assertThat(rpc.required("error").required("code").asInt()).isEqualTo(-32602);
            assertTransportError(post(http, endpoint, "{", token, null), 400, -32600);
        }
        finally {
            PackagedMcpHttp.reap(process);
        }
    }

    private static void assertTransportError(HttpResponse<String> response, int status, int code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = JSON.readTree(response.body());
        assertThat(body.size()).isOne();
        var error = body.required("jsonRpcError");
        assertThat(error.size()).isEqualTo(2);
        assertThat(error.required("code").asInt()).isEqualTo(code);
        assertThat(error.required("message").asText()).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({"unknown,streamable-http", "unknown,stdio", "STATELESS,stdio"})
    void unsupportedProtocolFailsStartup(String protocol, String transport, @TempDir Path directory) throws Exception {
        assertRejectedLaunch(directory, "spring.ai.mcp.server.protocol", "--policynim.mcp.transport=" + transport,
            "--spring.ai.mcp.server.protocol=" + protocol);
    }

    @ParameterizedTest
    @CsvSource({
        "unknown,--server.port=0",
        "grounded-v1,--spring.ai.mcp.server.protocol=SSE",
        "grounded-v1,--spring.ai.mcp.server.protocol=STATELESS",
        "grounded-v1,--spring.ai.mcp.server.stdio=true",
        "grounded-v1,--spring.main.web-application-type=none"
    })
    void unavailableContractFailsBeforeServing(String contract, String option, @TempDir Path directory) throws Exception {
        assertRejectedLaunch(directory, "policynim.mcp.contract", "--policynim.mcp.contract=" + contract, option);
    }

    @Test
    void authenticatedGroundedServerRequiresAPublicOrigin(@TempDir Path directory) throws Exception {
        assertRejectedLaunch(directory, "policynim.mcp.public-base-url", "--policynim.mcp.contract=grounded-v1",
            "--policynim.mcp.auth.enabled=true", "--policynim.mcp.auth.bearer-token=" + UUID.randomUUID());
    }

    private static void assertRejectedLaunch(Path directory, String setting, String... overrides) throws Exception {
        var launch = PackagedMcpHttp.jarCommand(directory, Map.of("SERVER_ADDRESS", "127.0.0.1", "SERVER_PORT", "0"),
            overrides);
        Process process = launch.redirectOutput(directory.resolve("startup.log").toFile()).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
            assertThat(directory.resolve("startup.log")).content().contains(setting).doesNotContain("Tomcat started on port");
        }
        finally {
            PackagedMcpHttp.reap(process);
        }
    }
}
