package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundedStdioRepliesTests {

    @Test
    void boundaryRefusalWaitsForTheLegacySdkDelimiterAndFlush() throws Exception {
        var sink = new ByteArrayOutputStream();
        var replies = new GroundedStdioReplies(sink, failure -> { throw new AssertionError(failure); });
        var attempted = new CountDownLatch(1);
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            synchronized (replies) {
                // StdioServerTransportProvider 2.2.0 holds this monitor across all three operations.
                replies.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".getBytes(StandardCharsets.UTF_8));
                worker.submit(() -> {
                    attempted.countDown();
                    replies.writeBoundary("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32600,\"message\":\"Invalid request\"}}\n"
                        .getBytes(StandardCharsets.UTF_8));
                    return null;
                });
                assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
                replies.write('\n');
                replies.flush();
            }
        }
        var lines = sink.toString(StandardCharsets.UTF_8).lines().toList();
        var json = new ObjectMapper();
        assertThat(lines).hasSize(2);
        assertThat(json.readTree(lines.get(0)).path("id").asInt()).isEqualTo(1);
        assertThat(json.readTree(lines.get(1)).path("id").asInt()).isEqualTo(2);
    }

    @Test
    void boundaryRefusalPropagatesAnOutputFailureRecordedByPrintStream() {
        var sink = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException { throw new IOException("Output closed"); }
        });
        var recorded = new AtomicReference<IOException>();
        var replies = new GroundedStdioReplies(sink, recorded::set);
        assertThatThrownBy(() -> replies.writeBoundary("{}\n".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IOException.class).isSameAs(recorded.get());
    }
}
