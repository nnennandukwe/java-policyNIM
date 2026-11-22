package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
            // # non-English test data
            String clientName = "ポリシー";
            repeat.setContent("""
                {"jsonrpc":"2.0","params":{"_meta":{"method":"tools/list","id":null},"clientInfo":{"name":"%s",
                "version":"1"},"capabilities":{},"protocolVersion":"2025-06-18"},"method":"initialize","id":"second"}
                """.formatted(clientName).getBytes(StandardCharsets.UTF_16LE));
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

    @Test
    void rejectedRequestsReuseTheSdkTransportAndTheNextInitializeStillIssuesASession() throws Exception {
        var transport = new SessionHttpServlet("/mcp", Duration.ofMinutes(30), null, false);
        var server = McpServer.sync(transport).serverInfo("fixture", "1").build();
        String list = """
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
            """;
        try (var builders = mockStatic(HttpServletStreamableServerTransportProvider.class, CALLS_REAL_METHODS)) {
            assertThat(statusFor(transport, "unknown")).isEqualTo(404);
            var unknown = post(transport, list, "unknown");
            assertThat(unknown.getStatus()).isEqualTo(404);
            assertThat(unknown.getContentAsString()).contains("\"code\":-32603").doesNotContain("stackTrace", "unknown");
            var missing = post(transport, list, null);
            assertThat(missing.getStatus()).isEqualTo(400);
            assertThat(missing.getContentAsString()).contains("\"code\":-32601");
            var malformed = post(transport, "{", "unknown");
            assertThat(malformed.getStatus()).isEqualTo(400);
            assertThat(malformed.getContentAsString()).contains("\"code\":-32600").doesNotContain("stackTrace");
            builders.verify(HttpServletStreamableServerTransportProvider::builder, never());

            var initialized = post(transport, INITIALIZE, "unknown");
            assertThat(initialized.getStatus()).isEqualTo(200);
            assertThat(initialized.getHeader(HttpHeaders.MCP_SESSION_ID)).isNotBlank().isNotEqualTo("unknown");
            builders.verify(HttpServletStreamableServerTransportProvider::builder);
        }
        finally {
            server.closeGracefully();
        }
    }

    @Test
    void headKeepsTheExistingSessionListenerAndTheSessionUsable() throws Exception {
        var transport = new SessionHttpServlet("/mcp", Duration.ofMinutes(30), null, false);
        var server = McpServer.sync(transport).serverInfo("fixture", "1").build();
        try {
            String session = post(transport, INITIALIZE, null).getHeader(HttpHeaders.MCP_SESSION_ID);
            var listener = sessionRequest("GET", session);
            var listening = new MockHttpServletResponse();
            transport.service(listener, listening);
            assertThat(listening.getStatus()).isEqualTo(200);
            assertThat(listener.isAsyncStarted()).isTrue();

            var head = sessionRequest("HEAD", session);
            var rejected = new MockHttpServletResponse();
            transport.service(head, rejected);
            assertThat(rejected.getStatus()).isEqualTo(405);
            assertThat(rejected.getHeader("Allow")).contains("GET", "POST", "DELETE").doesNotContain("HEAD");
            assertThat(rejected.getContentAsByteArray()).isEmpty();
            assertThat(head.isAsyncStarted()).isFalse();
            assertThat(listener.isAsyncStarted()).isTrue();

            var notification = post(transport, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """, session);
            assertThat(notification.getStatus()).isEqualTo(202);
            var deleted = new MockHttpServletResponse();
            transport.service(sessionRequest("DELETE", session), deleted);
            assertThat(deleted.getStatus()).isEqualTo(200);
            assertThat(listener.isAsyncStarted()).isFalse();
        }
        finally {
            server.closeGracefully();
        }
    }

    private static MockHttpServletRequest sessionRequest(String method, String session) {
        var request = new MockHttpServletRequest(method, "/mcp");
        request.setAsyncSupported(true);
        request.addHeader("Accept", "text/event-stream");
        request.addHeader(HttpHeaders.MCP_SESSION_ID, session);
        return request;
    }

    private static MockHttpServletResponse post(SessionHttpServlet transport, String body, String session) throws Exception {
        var request = initializeRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (session != null) {
            request.addHeader(HttpHeaders.MCP_SESSION_ID, session);
        }
        var response = new MockHttpServletResponse();
        transport.service(request, response);
        return response;
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
