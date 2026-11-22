package io.policynim.mcp.transport;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Legacy stateless clients can listen for SSE comments until the connection or the application closes. */
final class GroundedHttpListeners implements AutoCloseable {
    private static final byte[] COMMENT = ": ping\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int HEARTBEAT_SECONDS = 15;
    private final int capacity;
    private final Set<Listener> active = new HashSet<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("policynim-mcp-sse-heartbeat").factory());
    private boolean closed;

    GroundedHttpListeners() {
        this(1024);
    }

    GroundedHttpListeners(int capacity) {
        this.capacity = capacity;
        timer.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    void open(HttpServletRequest request, HttpServletResponse response) throws IOException {
        synchronized (this) {
            if (closed) {
                response.setStatus(503);
                return;
            }
            if (active.size() >= capacity) {
                response.setStatus(429);
                response.setIntHeader("Retry-After", HEARTBEAT_SECONDS);
                return;
            }
            var context = request.startAsync(request, response);
            var listener = new Listener(context);
            active.add(listener);
            try {
                context.setTimeout(0);
                context.addListener(listener);
                response.setContentType("text/event-stream");
                response.setHeader("Cache-Control", "no-cache, no-transform");
                response.setHeader("X-Accel-Buffering", "no");
                listener.output = response.getOutputStream();
                listener.output.setWriteListener(listener);
            }
            catch (IOException | RuntimeException failure) {
                listener.finish();
                throw failure;
            }
        }
    }

    private synchronized List<Listener> snapshot() {
        return List.copyOf(active);
    }

    private void heartbeat() {
        snapshot().forEach(Listener::ping);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        timer.shutdownNow();
        snapshot().forEach(Listener::finish);
    }

    private final class Listener implements AsyncListener, WriteListener {
        private final AsyncContext context;
        private ServletOutputStream output;
        private boolean started;
        private boolean finished;

        Listener(AsyncContext context) {
            this.context = context;
        }

        @Override
        public synchronized void onWritePossible() throws IOException {
            if (!started && !finished && output.isReady()) {
                started = true;
                output.flush();
            }
        }

        synchronized void ping() {
            if (!started || finished) {
                return;
            }
            try {
                if (output.isReady()) {
                    output.write(COMMENT);
                    if (output.isReady()) {
                        output.flush();
                    }
                }
            }
            catch (IOException | RuntimeException disconnected) {
                finish();
            }
        }

        private synchronized boolean retire() {
            if (finished) {
                return false;
            }
            finished = true;
            synchronized (GroundedHttpListeners.this) {
                active.remove(this);
            }
            return true;
        }

        void finish() {
            if (!retire()) {
                return;
            }
            try {
                context.complete();
            }
            catch (IllegalStateException alreadyCompleted) {
                // The container can complete an abandoned connection before its error callback arrives.
            }
        }

        @Override
        public void onError(Throwable failure) { finish(); }

        @Override
        public void onError(AsyncEvent event) { finish(); }

        @Override
        public void onTimeout(AsyncEvent event) { finish(); }

        @Override
        public void onComplete(AsyncEvent event) { retire(); }

        @Override
        public void onStartAsync(AsyncEvent event) { }
    }
}
