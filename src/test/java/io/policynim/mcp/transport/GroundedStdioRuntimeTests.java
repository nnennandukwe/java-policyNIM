package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.policynim.config.PolicyNimProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroundedStdioRuntimeTests {
    private static final String META = """
        "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}""";
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eofAfterRequestWaitsForItsResponseToFlush(boolean modern) throws Exception {
        String request = modern
            ? "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"server/discover\",\"params\":{" + META + "}}"
            : """
              {"jsonrpc":"2.0","id":41,"method":"initialize","params":{"protocolVersion":"2025-11-25",
              "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""".replace("\n", "");
        var output = new ByteArrayOutputStream();
        var fixture = runtime(new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)), output);
        assertTimeoutPreemptively(Duration.ofSeconds(5), fixture::run);
        List<String> lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(1);
        JsonNode result = JSON.readTree(lines.getFirst());
        assertThat(result.path("id").asInt()).isEqualTo(41);
        if (modern) {
            assertThat(result.at("/result/resultType").asText()).isEqualTo("complete");
        }
        else {
            assertThat(result.at("/result/protocolVersion").asText()).isEqualTo("2025-11-25");
        }
    }

    @Test
    void eofDuringAnAcceptedCallWaitsForItsHandlerAndFlushedResponse() throws Exception {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"tools/list\",\"params\":{" + META + "}}\n";
        var input = new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();
        var tools = mock(McpSyncToolRepository.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(tools.list(any(), isNull())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fixture tool timed out");
            }
            return new ToolsPage(List.of(), null, null, null);
        });
        var settings = new McpServerSettings(new MockEnvironment(), new PolicyNimProperties());
        try (var fixture = new GroundedStdioRuntime(input, output, settings, List.of(), tools);
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = worker.submit(() -> { fixture.run(); return null; });
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(run.isDone()).isFalse();
                assertThat(output.size()).isZero();
            }
            finally {
                release.countDown();
            }
            run.get(3, TimeUnit.SECONDS);
            JsonNode frame = JSON.readTree(output.toByteArray());
            assertThat(frame.path("id").asInt()).isEqualTo(17);
            assertThat(frame.at("/result/tools").isArray()).isTrue();
        }
    }

    @Test
    void inputFailureReleasesTheWaitingRuntimeAndReachesTheCaller() {
        var failure = new IOException("Test input closed unexpectedly");
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }
        };
        var fixture = runtime(broken, new ByteArrayOutputStream());
        assertTimeoutPreemptively(Duration.ofSeconds(3),
            () -> assertThatThrownBy(fixture::run).isSameAs(failure));
    }

    @Test
    void closesIdleSelectionAndCanCloseAgain() throws Exception {
        var input = new PipedInputStream();
        try (var peer = new PipedOutputStream(input); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = new ByteArrayOutputStream();
            var fixture = runtime(input, output);
            var serving = worker.submit(() -> { fixture.run(); return null; });
            fixture.close();
            serving.get(3, TimeUnit.SECONDS);
            fixture.close();
            assertThat(output.size()).isZero();
        }
    }

    private static GroundedStdioRuntime runtime(InputStream input, ByteArrayOutputStream output) {
        var settings = new McpServerSettings(new MockEnvironment()
            .withProperty("spring.ai.mcp.server.capabilities.tool", "false")
            .withProperty("spring.ai.mcp.server.request-timeout", "2s"), new PolicyNimProperties());
        return new GroundedStdioRuntime(input, output, settings, List.of(), mock(McpSyncToolRepository.class));
    }
}
