package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class LegacySseServlet extends HttpServlet {

    private final HttpServletSseServerTransportProvider transport;
    private final ReentrantReadWriteLock sessionSetup = new ReentrantReadWriteLock(true);
    private boolean closed;

    LegacySseServlet(HttpServletSseServerTransportProvider transport) {
        this.transport = transport;
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
