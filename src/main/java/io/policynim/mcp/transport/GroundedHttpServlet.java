package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class GroundedHttpServlet extends HttpServlet {
    static final int MAX_BODY = 4 * 1024 * 1024;
    private static final String VERSION_HEADER = "MCP-Protocol-Version";
    private static final String MODERN = McpSchema.LATEST_PROTOCOL_VERSION;
    private static final List<String> ROUTING_HEADERS = List.of(VERSION_HEADER, "Mcp-Method", "Mcp-Name");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String endpoint;
    private final HttpServletStatelessServerTransport legacy;
    private final HttpServletMcpTransport modern;
    private final GroundedHttpListeners listeners = new GroundedHttpListeners();
    private final AtomicBoolean closed = new AtomicBoolean();

    GroundedHttpServlet(String endpoint, HttpServletStatelessServerTransport legacy, HttpServletMcpTransport modern) {
        this.endpoint = endpoint;
        this.legacy = legacy;
        this.modern = modern;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        legacy.init(config);
        modern.init(config);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (closed.get()) {
            response.setStatus(503);
            return;
        }
        if (request.getRequestURI().equals(request.getContextPath() + endpoint + "/")) {
            String url = request.getRequestURL().toString();
            String query = request.getQueryString();
            response.setHeader("Location", url.substring(0, url.length() - 1) + (query == null ? "" : "?" + query));
            response.setStatus(307);
            return;
        }
        String version = request.getHeader(VERSION_HEADER);
        boolean isModern = version != null && !GroundedHttpConfiguration.HANDSHAKE_VERSIONS.contains(version);
        if (!"POST".equals(request.getMethod())) {
            otherMethod(request, response, isModern);
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY) {
            response.setStatus(413);
            return;
        }
        if (!isJsonContentType(request.getContentType())) {
            response.setStatus(400);
            response.setContentType("text/plain");
            response.getWriter().write("Invalid Content-Type header");
            return;
        }
        if (!accepts(request, MediaType.APPLICATION_JSON)) {
            if (isModern) {
                response.setStatus(406);
            }
            else {
                error(response, 406, null, -32600, "Not Acceptable: Client must accept application/json");
            }
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            response.setStatus(413);
            return;
        }
        var replay = new GroundedHttpRequest(request, body);
        GroundedHttpRequest.Envelope message;
        try {
            message = replay.envelope(JSON);
        }
        catch (IOException invalidJson) {
            error(response, 400, null, -32700, "Parse error");
            return;
        }
        if (!message.validRequest()) {
            if (!isModern && message.response() && message.id() != null && "2.0".equals(message.version())) {
                response.setStatus(202);
                return;
            }
            error(response, 400, null, isModern ? -32600 : -32602, "Expected a JSON-RPC request or notification");
            return;
        }
        if (isModern && "subscriptions/listen".equals(message.method()) && !accepts(request, MediaType.TEXT_EVENT_STREAM)) {
            response.setStatus(406);
            return;
        }
        for (String header : ROUTING_HEADERS) {
            var values = Collections.list(request.getHeaders(header));
            if (values.size() > 1 || (VERSION_HEADER.equals(header) && !values.isEmpty() && values.getFirst().contains(","))) {
                error(response, 400, message.id(), -32020, header.toLowerCase(Locale.ROOT) + " header appears more than once");
                return;
            }
        }
        if (isModern && message.id() == null && !MODERN.equals(version)) {
            var rejection = rpcError(null, -32022, "Unsupported protocol version");
            rejection.withObject("/error/data").put("requested", version).putArray("supported").add(MODERN);
            write(response, 400, rejection);
            return;
        }
        if (isModern && message.id() != null && (message.metadata() == null || !message.metadata().isObject()
            || !message.metadata().has("io.modelcontextprotocol/protocolVersion")
            || !message.metadata().has("io.modelcontextprotocol/clientCapabilities"))) {
            error(response, 400, message.id(), -32602, "The request requires protocolVersion and clientCapabilities metadata");
            return;
        }
        (isModern ? modern : legacy).service(replay, response);
    }

    private void otherMethod(HttpServletRequest request, HttpServletResponse response, boolean isModern) throws IOException {
        if (!isModern && "GET".equals(request.getMethod())) {
            if (!accepts(request, MediaType.TEXT_EVENT_STREAM)) {
                error(response, 406, null, -32600, "Not Acceptable: Client must accept text/event-stream");
                return;
            }
            listeners.open(request, response);
        }
        else if (!isModern && "DELETE".equals(request.getMethod())) {
            error(response, 405, null, -32600, "Method Not Allowed: Session termination not supported");
        }
        else {
            response.setStatus(405);
            response.setHeader("Allow", isModern ? "POST" : "GET, POST, DELETE");
        }
    }

    private static boolean isJsonContentType(String contentType) {
        try {
            return contentType != null && MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON);
        }
        catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean accepts(HttpServletRequest request, MediaType wanted) {
        try {
            return MediaType.parseMediaTypes(Collections.list(request.getHeaders("Accept"))).stream()
                .anyMatch(range -> range.getQualityValue() > 0 && range.includes(wanted));
        }
        catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static void error(HttpServletResponse response, int status, JsonNode id, int code, String message) throws IOException {
        write(response, status, rpcError(id, code, message));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode rpcError(JsonNode id, int code, String message) {
        var reply = JSON.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", id);
        reply.putObject("error").put("code", code).put("message", message);
        return reply;
    }

    private static void write(HttpServletResponse response, int status, JsonNode reply) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(reply));
    }

    @Override
    public void destroy() {
        if (closed.compareAndSet(false, true)) {
            try {
                listeners.close();
            }
            finally {
                try {
                    modern.destroy();
                }
                finally {
                    legacy.destroy();
                }
            }
        }
    }
}
