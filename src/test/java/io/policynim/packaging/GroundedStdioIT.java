package io.policynim.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.support.PackagedMcpHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedStdioIT {

    private static final String MODERN_LIST = """
        {"jsonrpc":"2.0","id":%d,"method":"tools/list","params":{"_meta":{\
        "io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String INIT = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
        "capabilities":{},"clientInfo":{"name":"package-test","version":"1"},\
        "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""";
    private static final String LEGACY_LIST = """
        {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}""";
    private static final int FRAME_BYTES = 4 * 1024 * 1024;

    @Test
    void terminationStopsAnInitializedServerWhileItsStdinRemainsOpen(@TempDir Path directory) throws Exception {
        var launch = PackagedMcpHttp.jarCommand(directory, Map.of(
            "POLICYNIM_MCP_TRANSPORT", "stdio", "POLICYNIM_MCP_CONTRACT", "grounded-v1"),
            "--spring.ai.mcp.server.name=Parity fixture", "--spring.ai.mcp.server.instructions=Use the local corpus.");
        Process child = launch.redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var stdin = child.outputWriter(UTF_8);
                stdin.write(INIT + "\n");
                stdin.flush();
                var stdout = child.inputReader(UTF_8);
                var reply = workers.submit(stdout::readLine).get(45, TimeUnit.SECONDS);
                var frame = new ObjectMapper().readTree(reply);
                assertEquals(1, frame.required("id").intValue());
                JsonNode result = frame.required("result");
                assertEquals("Parity fixture", result.required("serverInfo").required("name").asText());
                assertEquals("Use the local corpus.", result.required("instructions").asText());
                stdin.write(PackagedMcpHttp.INITIALIZED + "\n");
                stdin.flush();
                // Signal through the process handle to keep the parent's pipe descriptors open.
                assertTrue(child.toHandle().destroy());
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "An idle stdin reader prevented shutdown.");
                assertTrue(Set.of(0, 143).contains(child.exitValue()), "The jar failed during shutdown.");
            }
            finally {
                PackagedMcpHttp.reap(child);
            }
        }
    }

    @Test
    void modernDirectRequestFollowsNonrequestsAndOnlyAnOversizedLineIsDropped(@TempDir Path directory) throws Exception {
        String notification = PackagedMcpHttp.INITIALIZED.strip();
        String oversized = MODERN_LIST.formatted(5) + " ".repeat(FRAME_BYTES);
        var frames = PackagedMcpStdio.exchange(directory, Map.of(), List.of(
            (notification + "\n").repeat(9) + "{", MODERN_LIST.formatted(2), INIT, oversized, MODERN_LIST.formatted(4)
        ));
        assertEquals(Set.of(1, 2, 4), frames.keySet());
        assertToolList(frames.get(2), true);
        var error = frames.get(1).required("error");
        assertEquals(-32022, error.required("code").asInt());
        assertEquals("2025-11-25", error.required("data").required("requested").asText());
        assertToolList(frames.get(4), true);
    }

    @Test
    void pipelinedLegacySessionRejectsModernAndDrainsTheFollowingRequestAtEof(@TempDir Path directory)
        throws Exception {
        var frames = PackagedMcpStdio.exchange(directory, Map.of(),
            List.of(INIT, PackagedMcpHttp.INITIALIZED, MODERN_LIST.formatted(2), LEGACY_LIST));
        assertEquals(Set.of(1, 2, 3), frames.keySet());
        assertEquals("2025-11-25", frames.get(1).required("result").required("protocolVersion").asText());
        assertEquals(-32600, frames.get(2).required("error").required("code").asInt());
        assertToolList(frames.get(3), false);
    }

    private static void assertToolList(JsonNode frame, boolean modern) {
        JsonNode result = frame.required("result");
        assertEquals(modern, result.has("resultType"));
        if (modern) {
            assertEquals("complete", result.required("resultType").asText());
        }
        var names = StreamSupport.stream(result.required("tools").spliterator(), false)
            .map(tool -> tool.required("name").asText()).sorted().toList();
        assertEquals(List.of("policy_preflight", "policy_search"), names);
    }
}
