package io.policynim.packaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.support.PackagedMcpHttp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackagedMcpStdio {

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PackagedMcpStdio() {
    }

    static Map<Integer, JsonNode> exchange(Path directory, Map<String, String> storage, List<String> lines)
        throws Exception {
        var environment = new HashMap<>(storage);
        environment.put("POLICYNIM_MCP_TRANSPORT", "stdio");
        environment.put("POLICYNIM_MCP_CONTRACT", "grounded-v1");
        try (var port = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Process child = PackagedMcpHttp.jarCommand(directory, environment,
                    "--server.address=127.0.0.1", "--server.port=" + port.getLocalPort())
                .redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                var output = workers.submit(() -> readFrames(child));
                var input = workers.submit(() -> {
                    try (var stdin = child.outputWriter(UTF_8)) {
                        for (String line : lines) {
                            stdin.write(line);
                            stdin.write('\n');
                        }
                    }
                    return true;
                });
                input.get(45, TimeUnit.SECONDS);
                assertTrue(child.waitFor(15, TimeUnit.SECONDS), "The jar stayed alive after stdin EOF.");
                var frames = output.get(5, TimeUnit.SECONDS);
                assertEquals(0, child.exitValue(), "The grounded stdio jar exited unsuccessfully.");
                return frames;
            }
            finally {
                PackagedMcpHttp.reap(child);
            }
        }
    }

    private static Map<Integer, JsonNode> readFrames(Process child) throws IOException {
        var frames = new HashMap<Integer, JsonNode>();
        try (var stdout = child.inputReader(UTF_8)) {
            String line;
            while ((line = stdout.readLine()) != null) {
                JsonNode frame = JSON.readTree(line);
                assertEquals("2.0", frame.required("jsonrpc").textValue());
                assertTrue(frame.required("id").isInt(), "Expected a correlated response.");
                assertTrue(frame.has("result") != frame.has("error"), "Expected either a result or an error.");
                assertNull(frames.put(frame.get("id").intValue(), frame), "Received a duplicate response.");
            }
        }
        return frames;
    }
}
