package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class LegacySseServlet extends HttpServlet {

    private final HttpServletSseServerTransportProvider transport;
    private final Map<String, String> allowedMethods;
    private final ReentrantReadWriteLock sessionSetup = new ReentrantReadWriteLock(true);
    private boolean closed;

    LegacySseServlet(HttpServletSseServerTransportProvider transport, String events, String messages) {
        this.transport = transport;
        allowedMethods = events.equals(messages) ? Map.of(events, "GET, POST") : Map.of(events, "GET", messages, "POST");
    }

    String[] urlMappings() {
        return allowedMethods.keySet().toArray(String[]::new);
    }

    McpServerTransportProvider transport() {
        return transport;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        transport.init(config);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // HttpServlet's default HEAD handler calls GET, which would create a session with no event reader.
        if ("HEAD".equals(request.getMethod())) {
            response.setHeader("Allow", allowedMethods.get(request.getHttpServletMapping().getPattern()));
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            transport.service(request, response);
            return;
        }
        // The SDK flushes headers before registering the session. A GET returns once it opens the async stream.
        sessionSetup.readLock().lock();
        try {
            transport.service(request, response);
        }
        finally {
            sessionSetup.readLock().unlock();
        }
    }

    @Override
    public void destroy() {
        sessionSetup.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                transport.destroy();
            }
        }
        finally {
            sessionSetup.writeLock().unlock();
        }
    }
}
