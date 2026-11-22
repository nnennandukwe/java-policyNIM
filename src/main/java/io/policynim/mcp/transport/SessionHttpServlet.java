package io.policynim.mcp.transport;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Owns the SDK servlet and its session together so expiry can reclaim both. */
final class SessionHttpServlet extends HttpServlet implements McpStreamableServerTransportProvider {

    private static final Logger logger = LoggerFactory.getLogger(SessionHttpServlet.class);

    // Match the SDK 2.2.0 servlet's per-request limit for repeated initialize requests.
    private static final int REQUEST_MAX_BYTES = 16 * 1024 * 1024;
    private final Map<String, Channel> sessions = new HashMap<>();
    private final String path;
    private final Duration idleTimeout;
    private final boolean disallowDelete;
    private final ObjectMapper json = LegacyTransportJson.objectMapper();
    private final JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(json);
    private final HttpServletStreamableServerTransportProvider rejectionTransport;
    private final Scheduler timer;
    private final KeepAliveScheduler keepAlive;
    private volatile McpStreamableServerSession.Factory factory;
    private volatile boolean closed;

    SessionHttpServlet(String path, Duration idleTimeout, Duration keepAliveInterval, boolean disallowDelete) {
        if (!idleTimeout.isPositive() || (keepAliveInterval != null && !keepAliveInterval.isPositive())) {
            throw new IllegalArgumentException("The MCP session idle timeout and keep-alive interval must be positive.");
        }
        this.path = path;
        this.idleTimeout = idleTimeout;
        this.disallowDelete = disallowDelete;
        long nanos = idleTimeout.toNanos();
        // This delegate has no session factory or maintenance tasks. The SDK owns its rejection responses.
        rejectionTransport = newSdkTransport();
        timer = Schedulers.fromExecutorService(Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("policynim-mcp-session-maintenance").factory()));
        // A peer that drops a silent TCP stream is observable only after a write. Probe idle
        // listeners at the expiry interval unless the operator configured regular heartbeats.
        Duration heartbeat = keepAliveInterval == null ? idleTimeout : keepAliveInterval;
        keepAlive = KeepAliveScheduler.builder(() -> Flux.fromIterable(snapshot())
                .filter(channel -> keepAliveInterval != null || System.nanoTime() - channel.lastAccess >= nanos)
                .map(channel -> channel.session).filter(McpStreamableServerSession::hasListeningStream)
                .cast(McpSession.class))
            .scheduler(timer).initialDelay(heartbeat).interval(heartbeat)
            .onPingFailure(session -> ((McpStreamableServerSession) session).releaseListeningStream())
            .build();
        try {
            timer.schedulePeriodically(this::expireIdle, nanos, nanos, TimeUnit.NANOSECONDS);
            keepAlive.start();
        }
        catch (RuntimeException | Error failure) {
            timer.dispose();
            throw failure;
        }
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        this.factory = factory;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        // The servlet default routes HEAD through GET, which would replace the session's listener.
        if ("HEAD".equals(request.getMethod())) {
            response.setHeader("Allow", disallowDelete ? "GET, POST" : "GET, POST, DELETE");
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if ("POST".equals(request.getMethod()) && request.getContentLengthLong() > REQUEST_MAX_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        String id = request.getHeader(HttpHeaders.MCP_SESSION_ID);
        Channel channel;
        synchronized (this) {
            if (closed || factory == null) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
            channel = sessions.get(id);
            if (channel != null) {
                channel.inFlight++;
            }
        }
        try {
            // The old transport issues a new ID even when initialize carries a live session ID.
            // Keep that new session independent of the one the caller already owns.
            if ("GET".equals(request.getMethod()) || "POST".equals(request.getMethod())) {
                request = withSdkAccept(request);
            }
            if (canInitialize(request)) {
                byte[] body = request.getInputStream().readNBytes(REQUEST_MAX_BYTES + 1);
                if (body.length > REQUEST_MAX_BYTES) {
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    return;
                }
                request = new ReplayRequest(request, body);
                if (initializes(body, request.getCharacterEncoding())) {
                    serveFresh(request, response);
                    return;
                }
            }
            if (channel == null) {
                rejectionTransport.service(request, new TransportResponse(response));
                return;
            }
            channel.delegate.service(request, new TransportResponse(response));
            if ("DELETE".equals(request.getMethod()) && response.getStatus() == HttpServletResponse.SC_OK) {
                synchronized (this) {
                    sessions.remove(id, channel);
                }
                channel.close();
            }
        }
        finally {
            if (channel != null) {
                synchronized (this) {
                    channel.inFlight--;
                    channel.lastAccess = System.nanoTime();
                }
            }
        }
    }

    private static HttpServletRequest withSdkAccept(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Accept"));
        if (headers.isEmpty()) {
            return request;
        }
        // SDK 2.2.0 compares raw substrings. Give it only the supported media ranges that
        // the client accepts, without treating a quoted parameter value as another range.
        String accepted;
        try {
            accepted = MediaType.parseMediaTypes(headers).stream()
                .filter(range -> range.getQualityValue() > 0)
                .filter(range -> range.equalsTypeAndSubtype(MediaType.APPLICATION_JSON)
                    || range.equalsTypeAndSubtype(MediaType.TEXT_EVENT_STREAM))
                .map(range -> range.getType() + "/" + range.getSubtype())
                .distinct()
                .collect(Collectors.joining(", "));
        }
        catch (IllegalArgumentException malformed) {
            accepted = "";
        }
        if (headers.size() == 1 && headers.getFirst().equals(accepted)) {
            return request;
        }
        String sdkAccept = accepted;
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return "Accept".equalsIgnoreCase(name) ? sdkAccept : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return "Accept".equalsIgnoreCase(name) ? Collections.enumeration(List.of(sdkAccept)) : super.getHeaders(name);
            }
        };
    }

    private static boolean canInitialize(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String accept = request.getHeader("Accept");
        String contentType = request.getContentType();
        if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream")
            || contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return "application/json".equalsIgnoreCase(type.trim());
    }

    private boolean initializes(byte[] body, String encoding) {
        try {
            var charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            try (var parser = json.createParser(new InputStreamReader(new ByteArrayInputStream(body), charset))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return false;
                }
                boolean hasId = false;
                boolean initialize = false;
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    if ("method".equals(field)) {
                        initialize = value == JsonToken.VALUE_STRING && McpSchema.METHOD_INITIALIZE.equals(parser.getText());
                    }
                    else if ("id".equals(field)) {
                        hasId = value == JsonToken.VALUE_STRING || value == JsonToken.VALUE_NUMBER_INT;
                    }
                    parser.skipChildren();
                }
                return initialize && hasId;
            }
        }
        catch (IOException | IllegalArgumentException invalidMessage) {
            return false; // Let the SDK return its protocol error for the original body.
        }
    }

    private void serveFresh(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        var channel = new Channel();
        channel.delegate.setSessionFactory(initialization -> {
            var started = factory.startSession(initialization);
            channel.session = started.session();
            return started;
        });
        var output = new TransportResponse(response) {
            private PrintWriter writer;
            private boolean published;

            @Override
            public PrintWriter getWriter() throws IOException {
                writer = super.getWriter();
                return writer;
            }

            @Override
            public void setHeader(String name, String value) {
                if (HttpHeaders.MCP_SESSION_ID.equalsIgnoreCase(name)) {
                    synchronized (SessionHttpServlet.this) {
                        if (closed) {
                            throw new IllegalStateException("MCP server is shutting down");
                        }
                        channel.inFlight++;
                        sessions.put(value, channel);
                        published = true;
                    }
                }
                super.setHeader(name, value);
            }
        };
        try {
            channel.delegate.service(request, output);
        }
        finally {
            boolean issued = output.published && response.getStatus() == HttpServletResponse.SC_OK
                && output.writer != null && !output.writer.checkError();
            synchronized (this) {
                if (output.published) {
                    channel.inFlight--;
                    channel.lastAccess = System.nanoTime();
                    if (!issued) {
                        sessions.remove(channel.session.getId(), channel);
                    }
                }
            }
            if (!issued) {
                channel.close();
            }
        }
    }

    private void expireIdle() {
        var expired = new ArrayList<Channel>();
        synchronized (this) {
            long now = System.nanoTime();
            var entries = sessions.entrySet().iterator();
            while (entries.hasNext()) {
                var channel = entries.next().getValue();
                if (channel.inFlight == 0 && now - channel.lastAccess >= idleTimeout.toNanos()
                    && !channel.session.hasOpenStream()) {
                    entries.remove();
                    expired.add(channel);
                }
            }
        }
        expired.forEach(Channel::close);
    }

    private synchronized List<Channel> snapshot() {
        return List.copyOf(sessions.values());
    }

    @Override
    public List<String> protocolVersions() {
        return rejectionTransport.protocolVersions();
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return Flux.defer(() -> Flux.fromIterable(snapshot()))
            .flatMap(channel -> channel.delegate.notifyClients(method, params)).then();
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return Mono.defer(() -> {
            Channel channel;
            synchronized (this) {
                channel = sessions.get(sessionId);
            }
            return channel == null ? Mono.empty() : channel.delegate.notifyClient(sessionId, method, params);
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::destroy);
    }

    @Override
    public void destroy() {
        List<Channel> active;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            active = snapshot();
            sessions.clear();
        }
        keepAlive.shutdown();
        timer.dispose();
        rejectionTransport.destroy();
        active.forEach(Channel::close);
    }

    private HttpServletStreamableServerTransportProvider newSdkTransport() {
        return HttpServletStreamableServerTransportProvider.builder().mcpEndpoint(path).jsonMapper(mapper)
            .disallowDelete(disallowDelete).maxRequestSize(REQUEST_MAX_BYTES)
            .keepAliveInterval(null).sessionSweepInterval(null).build();
    }

    private final class Channel {
        private final HttpServletStreamableServerTransportProvider delegate = newSdkTransport();
        private McpStreamableServerSession session;
        private final AtomicBoolean disposed = new AtomicBoolean();
        private volatile long lastAccess = System.nanoTime();
        private int inFlight;

        void close() {
            if (disposed.compareAndSet(false, true)) {
                try {
                    delegate.closeGracefully().block();
                }
                catch (RuntimeException failure) {
                    logger.warn("Couldn't close an MCP HTTP session", failure);
                }
            }
        }
    }

    private static class TransportResponse extends HttpServletResponseWrapper {
        TransportResponse(HttpServletResponse response) {
            super(response);
        }

        // A container error dispatch can change an MCP error's status and payload.
        @Override
        public void sendError(int status, String message) {
            setStatus(status);
        }

        @Override
        public void sendError(int status) {
            setStatus(status);
        }
    }

    private static final class ReplayRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        ReplayRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bytes.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public int read() {
                    return bytes.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return bytes.read(buffer, offset, length);
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("MCP reads the bounded request body synchronously.");
                }
            };
        }
    }
}
