package io.policynim.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PackagedMcpHttp {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STARTED = Pattern.compile("Tomcat started on port (\\d+)");
    public static final String VERSION = "2025-06-18";
    public static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
        "capabilities":{},"clientInfo":{"name":"http-test","version":"1"}}}""";
    public static final String INITIALIZED = """
        {"jsonrpc":"2.0","method":"notifications/initialized"}""";

    private PackagedMcpHttp() {
    }

    public static ProcessBuilder jarCommand(Path directory, Map<String, String> environment, String... arguments) {
        var command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.home=" + directory, "-jar", Path.of("target", "policynim.jar").toAbsolutePath().toString()
        ));
        command.addAll(List.of(arguments));
        var launch = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        launch.environment().clear();
        launch.environment().putAll(environment);
        launch.environment().put("POLICYNIM_PROVIDER_NVIDIA_ENABLED", "false");
        return launch;
    }

    public static Process startServer(Path directory, Map<String, String> environment, String token) throws IOException {
        var launch = jarCommand(directory, environment, "--server.address=127.0.0.1", "--server.port=0");
        launch.environment().put("POLICYNIM_MCP_AUTH_ENABLED", "true");
        launch.environment().put("POLICYNIM_MCP_BEARER_TOKEN", token);
        return launch.start();
    }

    public static void reap(Process child) throws InterruptedException {
        if (child.isAlive()) {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Couldn't reap the test-owned jar process.");
        }
    }

    public static URI readBoundOrigin(Process child) throws Exception {
        var boundPort = new CompletableFuture<Integer>();
        Thread.ofVirtual().start(() -> {
            try (var log = child.inputReader(UTF_8)) {
                String line;
                while ((line = log.readLine()) != null) {
                    var match = STARTED.matcher(line);
                    if (match.find()) {
                        boundPort.complete(Integer.parseInt(match.group(1)));
                    }
                }
                boundPort.completeExceptionally(new IOException("The process exited before binding its HTTP port."));
            }
            catch (IOException error) {
                boundPort.completeExceptionally(error);
            }
        });
        return URI.create("http://127.0.0.1:" + boundPort.get(45, TimeUnit.SECONDS));
    }

    public static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json, text/event-stream");
    }

    public static HttpResponse<String> post(HttpClient http, URI endpoint, String frame, String token, String session)
        throws Exception {
        var builder = request(endpoint).header("Content-Type", "application/json");
        if (!frame.equals(INITIALIZE)) {
            builder.header("MCP-Protocol-Version", VERSION);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (session != null) {
            builder.header("Mcp-Session-Id", session);
        }
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(frame)).build(),
            HttpResponse.BodyHandlers.ofString(UTF_8));
    }

    public static HttpResponse<String> postJson(HttpClient http, URI endpoint, String body, String token,
        Map<String, String> headers) throws Exception {
        var request = request(endpoint).setHeader("Accept", "application/json")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::setHeader);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static JsonNode result(HttpResponse<String> response, int id) throws IOException {
        assertEquals(200, response.statusCode());
        String body = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            body = body.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5);
        }
        JsonNode frame = JSON.readTree(body);
        assertEquals("2.0", frame.path("jsonrpc").textValue());
        assertEquals(id, frame.path("id").intValue());
        assertTrue(frame.path("result").isObject(), "Expected a JSON-RPC result.");
        return frame.get("result");
    }
}
