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
    private static final int MAX_MISSED_WRITES = 2;
    private final int capacity;
    private final Set<Listener> active = new HashSet<>();
    private final ScheduledExecutorService timer;
    private boolean closed;

    GroundedHttpListeners() {
        this(1024);
    }

    GroundedHttpListeners(int capacity) {
        this(capacity, Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("policynim-mcp-sse-heartbeat").factory()));
    }

    GroundedHttpListeners(int capacity, ScheduledExecutorService timer) {
        this.capacity = capacity;
        this.timer = timer;
        timer.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    void open(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var listener = new Listener();
        int status = reserve(listener);
        if (status != 200) {
            response.setStatus(status);
            if (status == 429) {
                response.setIntHeader("Retry-After", HEARTBEAT_SECONDS);
            }
            return;
        }
        try {
            listener.start(request, response);
        }
        catch (IOException | RuntimeException failure) {
            listener.finish();
            throw failure;
        }
    }

    // Reserve capacity before allocating the container context. The pool lock only guards membership.
    private synchronized int reserve(Listener listener) {
        if (closed) {
            return 503;
        }
        if (active.size() >= capacity) {
            return 429;
        }
        active.add(listener);
        return 200;
    }

    private synchronized void release(Listener listener) {
        active.remove(listener);
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
        private AsyncContext context;
        private ServletOutputStream output;
        private boolean started;
        private boolean finished;
        private int missedWrites;

        void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
            var opened = request.startAsync(request, response);
            if (!attach(opened)) {
                // Shutdown or a missed-write deadline reclaimed the slot during startAsync.
                complete(opened);
                return;
            }
            opened.setTimeout(0);
            opened.addListener(this);
            response.setContentType("text/event-stream");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
            output = response.getOutputStream();
            output.setWriteListener(this);
        }

        private synchronized boolean attach(AsyncContext opened) {
            if (finished) {
                return false;
            }
            context = opened;
            return true;
        }

        @Override
        public synchronized void onWritePossible() throws IOException {
            if (!started && !finished && output.isReady()) {
                output.flush();
                started = true;
                missedWrites = 0;
            }
        }

        void ping() {
            try {
                synchronized (this) {
                    if (finished) {
                        return;
                    }
                    if (started && output.isReady()) {
                        output.write(COMMENT);
                        if (output.isReady()) {
                            output.flush();
                        }
                        missedWrites = 0;
                        return;
                    }
                    if (++missedWrites < MAX_MISSED_WRITES) {
                        return;
                    }
                }
            }
            catch (IOException | RuntimeException disconnected) {
                // The same retirement path handles a failed write and a stalled stream.
            }
            finish();
        }

        private void retire(boolean completeContext) {
            AsyncContext owned;
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
                owned = context;
            }
            release(this);
            if (completeContext && owned != null) {
                complete(owned);
            }
        }

        void finish() {
            retire(true);
        }

        private static void complete(AsyncContext context) {
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
        public void onComplete(AsyncEvent event) { retire(false); }

        @Override
        public void onStartAsync(AsyncEvent event) { }
    }
}
