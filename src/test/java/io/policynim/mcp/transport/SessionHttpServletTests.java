package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession.McpStreamableServerSessionInit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionHttpServletTests {

    private static final McpSchema.InitializeResult INITIALIZED = new McpSchema.InitializeResult(
        "2025-06-18", McpSchema.ServerCapabilities.builder().build(), new McpSchema.Implementation("fixture", "1"), null, null);
    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",        "capabilities":{},"clientInfo":{"name":"fixture","version":"1"}}}
        """;

    @Test
    void releasesTheSessionIfWritingTheInitializationResponseFails() throws Exception {
        var transport = new SessionHttpServlet("/mcp", Duration.ofMinutes(30), null, false);
        var session = session("unpublished");
        var response = new MockHttpServletResponse() {
            @Override
            public PrintWriter getWriter() {
                return new PrintWriter(new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("Client disconnected");
                    }
                });
            }
        };
        try {
            transport.setSessionFactory(request -> new McpStreamableServerSessionInit(session, Mono.just(INITIALIZED)));
            transport.service(initializeRequest(), response);
            verify(session).closeGracefully();
            assertThat(statusFor(transport, "unpublished")).isEqualTo(404);
        }
        finally {
            transport.destroy();
        }
    }

    @Test
    void anInitializationThatCompletesAfterShutdownCannotPublishItsId() throws Exception {
        var transport = new SessionHttpServlet("/mcp", Duration.ofMinutes(30), null, false);
        var session = session("interrupted");
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var response = new MockHttpServletResponse();
        try (var worker = Executors.newSingleThreadExecutor()) {
            transport.setSessionFactory(request -> new McpStreamableServerSessionInit(session, Mono.fromCallable(() -> {
                entered.countDown();
                if (!proceed.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for shutdown");
                }
                return INITIALIZED;
            })));
            var request = worker.submit(() -> {
                transport.service(initializeRequest(), response);
                return null;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                transport.destroy();
            }
            finally {
                proceed.countDown();
            }
            request.get(5, TimeUnit.SECONDS);
            assertThat(response.getHeader(HttpHeaders.MCP_SESSION_ID)).isNull();
            verify(session).closeGracefully();
            assertThat(statusFor(transport, "interrupted")).isEqualTo(503);
        }
        finally {
            transport.destroy();
        }
    }

    @Test
    void successiveAbandonedSessionsEachExpireOnTheOwnedTimer() throws Exception {
        var transport = new SessionHttpServlet("/mcp", Duration.ofMillis(50), null, false);
        try {
            for (String id : new String[]{"first", "second"}) {
                var expired = new CountDownLatch(1);
                var session = session(id);
                when(session.closeGracefully()).thenReturn(Mono.fromRunnable(expired::countDown));
                transport.setSessionFactory(request -> new McpStreamableServerSessionInit(session, Mono.just(INITIALIZED)));
                var response = new MockHttpServletResponse();
                transport.service(initializeRequest(), response);
                assertThat(response.getHeader(HttpHeaders.MCP_SESSION_ID)).isEqualTo(id);
                assertThat(expired.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(statusFor(transport, id)).isEqualTo(404);
            }
        }
        finally {
            transport.destroy();
        }
    }

    @Test
    void repeatedInitializeUsesTopLevelFieldsAndTheDeclaredEncoding() throws Exception {
        var transport = new SessionHttpServlet("/mcp", Duration.ofMinutes(30), null, false);
        var server = McpServer.sync(transport).serverInfo("fixture", "1").build();
        try {
            var first = new MockHttpServletResponse();
            transport.service(initializeRequest(), first);
            String original = first.getHeader(HttpHeaders.MCP_SESSION_ID);
            var repeat = initializeRequest();
            repeat.addHeader(HttpHeaders.MCP_SESSION_ID, original);
            repeat.setCharacterEncoding(StandardCharsets.UTF_16LE.name());
            repeat.setContent("""
                {"jsonrpc":"2.0","params":{"_meta":{"method":"tools/list","id":null},"clientInfo":{"name":"ポリシー",
                "version":"1"},"capabilities":{},"protocolVersion":"2025-06-18"},"method":"initialize","id":"second"}
                """.getBytes(StandardCharsets.UTF_16LE));
            var second = new MockHttpServletResponse();
            transport.service(repeat, second);
            String replacement = second.getHeader(HttpHeaders.MCP_SESSION_ID);
            assertThat(replacement).isNotBlank().isNotEqualTo(original);
            for (String id : new String[]{replacement, original}) {
                var delete = new MockHttpServletRequest("DELETE", "/mcp");
                delete.addHeader(HttpHeaders.MCP_SESSION_ID, id);
                var response = new MockHttpServletResponse();
                transport.service(delete, response);
                assertThat(response.getStatus()).isEqualTo(200);
            }
        }
        finally {
            server.closeGracefully();
        }
    }

    private static McpStreamableServerSession session(String id) {
        var session = mock(McpStreamableServerSession.class);
        when(session.getId()).thenReturn(id);
        when(session.closeGracefully()).thenReturn(Mono.empty());
        return session;
    }

    private static MockHttpServletRequest initializeRequest() {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContent(INITIALIZE.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static int statusFor(SessionHttpServlet transport, String id) throws Exception {
        var request = new MockHttpServletRequest("GET", "/mcp");
        request.addHeader("Accept", "text/event-stream");
        request.addHeader(HttpHeaders.MCP_SESSION_ID, id);
        var response = new MockHttpServletResponse();
        transport.service(request, response);
        return response.getStatus();
    }
}
