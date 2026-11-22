package io.policynim.packaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PackagedStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05",\
        "capabilities":{},"clientInfo":{"name":"stdio-test","version":"1"}}}""";
    private static final String INITIALIZED = """
        {"jsonrpc":"2.0","method":"notifications/initialized"}""";
    private static final String LIST_TOOLS = """
        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""";

    @ParameterizedTest
    @ValueSource(strings = {"POLICYNIM_MCP_TRANSPORT", "SPRING_AI_MCP_SERVER_STDIO"})
    void jarServesOnlyProtocolFramesWithoutAnHttpPortAndExitsOnEof(String setting, @TempDir Path directory)
        throws Exception {
        Path jar = Path.of("target", "policynim.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "Build the executable jar before running this test.");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");

        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessBuilder launch = new ProcessBuilder(
                java.toString(), "-Duser.home=" + directory, "-jar", jar.toString(),
                "--server.address=127.0.0.1", "--server.port=" + occupied.getLocalPort()
            ).directory(directory.toFile());
            launch.environment().clear();
            launch.environment().put(setting, setting.equals("POLICYNIM_MCP_TRANSPORT") ? "stdio" : "true");
            launch.environment().put("POLICYNIM_PROVIDER_NVIDIA_ENABLED", "false");
            Process child = launch.start();
            try {
                var diagnostics = readers.submit(() -> {
                    try (var stderr = child.errorReader(UTF_8)) {
                        // Consume every line without copying diagnostic values into the test report.
                        return stderr.lines().filter(line -> !line.isBlank()).count();
                    }
                });
                BufferedReader stdout = child.inputReader(UTF_8);
                BufferedWriter stdin = child.outputWriter(UTF_8);
                send(stdin, INITIALIZE);
                JsonNode initialized = readers.submit(() -> readReply(stdout, 1)).get(45, TimeUnit.SECONDS);
                assertEquals("2024-11-05", initialized.path("protocolVersion").textValue());

                send(stdin, INITIALIZED);
                send(stdin, LIST_TOOLS);
                JsonNode tools = readers.submit(() -> readReply(stdout, 2)).get(10, TimeUnit.SECONDS).path("tools");
                Set<String> names = new HashSet<>();
                tools.forEach(tool -> names.add(tool.path("name").textValue()));
                assertTrue(names.containsAll(Set.of("policy_search", "policy_preflight")), "Missing baseline tools.");

                var shutdownOutput = readers.submit(() -> {
                    String line;
                    while ((line = stdout.readLine()) != null) {
                        assertNotification(parseFrame(line));
                    }
                    return true;
                });
                stdin.close();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "The jar stayed alive five seconds after stdin EOF.");
                assertEquals(0, child.exitValue(), "The jar exited unsuccessfully after stdin EOF.");
                shutdownOutput.get(5, TimeUnit.SECONDS);
                assertTrue(diagnostics.get(5, TimeUnit.SECONDS) > 0, "Expected startup diagnostics on stderr.");
            }
            finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Couldn't reap the test-owned jar process.");
                }
            }
        }
    }

    private static void send(BufferedWriter stdin, String frame) throws IOException {
        stdin.write(frame);
        stdin.write('\n');
        stdin.flush();
    }

    private static JsonNode readReply(BufferedReader stdout, int id) throws IOException {
        String line;
        while ((line = stdout.readLine()) != null) {
            JsonNode frame = parseFrame(line);
            if (frame.has("id")) {
                assertTrue(frame.path("id").isIntegralNumber() && frame.path("id").longValue() == id,
                    "Unexpected JSON-RPC response ID.");
                assertTrue(frame.path("result").isObject() && !frame.has("error"), "Expected a successful response.");
                return frame.get("result");
            }
            assertNotification(frame);
        }
        return fail("The jar closed stdout before responding to request " + id + ".");
    }

    private static JsonNode parseFrame(String line) {
        assertFalse(line.isBlank(), "A blank stdout line interrupted the MCP stream.");
        JsonNode frame;
        try {
            frame = JSON.readTree(line);
        }
        catch (JsonProcessingException ignored) {
            return fail("A non-JSON stdout line interrupted the MCP stream.");
        }
        assertEquals("2.0", frame.path("jsonrpc").textValue(), "Expected a JSON-RPC frame.");
        return frame;
    }

    private static void assertNotification(JsonNode frame) {
        assertTrue(frame.path("method").isTextual() && !frame.has("id") && !frame.has("result") && !frame.has("error"),
            "Expected a JSON-RPC notification.");
    }
}
