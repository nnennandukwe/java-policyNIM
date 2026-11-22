package io.policynim.mcp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.transport.StdioMcpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.Pipe;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Serves one grounded stdio connection for the lifetime of the application's main thread. */
public final class GroundedStdioRuntime implements AutoCloseable {
    private final InputStream stdin;
    private final GroundedStdioReplies replies;
    private final McpServerSettings settings;
    private final List<McpServerFeatures.SyncToolSpecification> legacyTools;
    private final McpSyncToolRepository modernTools;
    private final CompletableFuture<Void> end = new CompletableFuture<>();
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private volatile boolean closed;
    private Pipe pipe;
    private Thread reader;
    private GroundedStdioInput frames;

    GroundedStdioRuntime(InputStream stdin, OutputStream stdout, McpServerSettings settings,
        List<McpServerFeatures.SyncToolSpecification> legacyTools, McpSyncToolRepository modernTools) {
        this.stdin = stdin;
        replies = new GroundedStdioReplies(stdout, this::failed);
        this.settings = settings;
        this.legacyTools = legacyTools;
        this.modernTools = modernTools;
    }

    public void run() throws IOException, InterruptedException {
        try {
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (pipe != null) {
                    throw new IllegalStateException("The grounded stdio runtime already started");
                }
                // System.in can block in a native read. Only this daemon owns it. Both SDKs read
                // an interruptible pipe, so closing the runtime also releases the legacy SDK's reader.
                pipe = Pipe.open();
                frames = new GroundedStdioInput(Channels.newInputStream(pipe.source()), replies::writeBoundary,
                    replies::accepted, replies::cancelled);
                reader = Thread.ofPlatform().daemon().name("policynim-stdin").start(this::copyStdin);
            }
            var era = frames.select();
            if (era != null && !closed) {
                var input = new DrainingInput(frames);
                var mapper = GroundedMcpJson.mapper();
                switch (era) {
                    case MODERN -> serveModern(input, mapper);
                    case LEGACY -> serveLegacy(input, mapper);
                }
            }
        }
        catch (ClosedByInterruptException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Interrupted while reading grounded stdio");
        }
        catch (IOException stopped) {
            if (!closed && failure.get() == null) {
                throw stopped;
            }
        }
        finally {
            close();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private void copyStdin() {
        try {
            stdin.transferTo(Channels.newOutputStream(pipe.sink()));
        }
        catch (IOException error) {
            if (!closed) {
                failed(error);
            }
        }
        finally {
            closeQuietly(pipe.sink());
        }
    }

    private void serveModern(InputStream input, McpJsonMapper mapper) throws IOException, InterruptedException {
        var server = settings.groundedModernServer(mapper, modernTools);
        try {
            var transport = new StdioMcpTransport(server, mapper, input, replies);
            try {
                await(transport.start().toFuture());
            }
            finally {
                transport.closeGracefully().block();
            }
        }
        finally {
            server.closeGracefully();
        }
    }

    private void serveLegacy(InputStream input, McpJsonMapper mapper) throws IOException, InterruptedException {
        var transport = new LegacyStdio(mapper, input, replies);
        replies.onInitializeReply((id, success) -> {
            if (success) {
                transport.initialized();
            }
            frames.initialized(id, success);
        });
        var capabilities = McpSchema.ServerCapabilities.builder();
        var builder = McpServer.sync(transport).serverInfo(settings.serverInfo()).instructions(settings.groundedInstructions())
            .requestTimeout(settings.requestTimeout()).validateToolInputs(true);
        if (settings.enabled("capabilities.tool")) {
            capabilities.tools(false);
            builder.tools(legacyTools);
        }
        io.modelcontextprotocol.server.McpSyncServer server;
        try {
            server = builder.capabilities(capabilities.build()).build();
        }
        catch (RuntimeException | Error startupFailure) {
            transport.closeGracefully().block();
            throw startupFailure;
        }
        try {
            await(end);
        }
        finally {
            server.closeGracefully();
        }
    }

    private static void await(CompletableFuture<Void> done) throws IOException, InterruptedException {
        try {
            done.get();
        }
        catch (ExecutionException failure) {
            throw new IOException("Grounded stdio transport stopped unexpectedly", failure.getCause());
        }
    }

    private void failed(IOException error) {
        failure.compareAndSet(null, error);
        close();
    }

    @Override
    public void close() {
        Pipe owned;
        Thread worker;
        GroundedStdioInput selected;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            owned = pipe;
            worker = reader;
            selected = frames;
        }
        replies.stopWaiting();
        end.complete(null);
        if (owned != null) {
            closeQuietly(owned.sink());
            closeQuietly(owned.source());
        }
        if (selected != null) {
            closeQuietly(selected);
        }
        if (worker != null) {
            worker.interrupt();
        }
        closeQuietly(stdin);
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        }
        catch (IOException alreadyClosed) {
            // Another owner may already have closed its end of the pipe.
        }
    }

    private final class DrainingInput extends FilterInputStream {
        DrainingInput(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            return observed(super.read());
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                return observed(super.read(bytes, offset, length));
            }
            catch (IOException error) {
                if (!closed) {
                    failed(error);
                }
                throw error;
            }
        }

        private int observed(int value) throws IOException {
            if (value == -1) {
                try {
                    replies.awaitFlush(settings.requestTimeout());
                }
                catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while draining MCP responses", stop);
                }
                end.complete(null);
            }
            return value;
        }
    }

    private static final class LegacyStdio extends StdioServerTransportProvider {
        private McpServerSession session;
        private boolean initialized;

        LegacyStdio(McpJsonMapper mapper, InputStream input, OutputStream output) {
            super(mapper, input, output);
        }

        @Override
        public void setSessionFactory(McpServerSession.Factory factory) {
            super.setSessionFactory(transport -> {
                session = factory.create(transport);
                return session;
            });
        }

        void initialized() {
            if (!initialized) {
                // Python accepts a request immediately after initialize. Complete the SDK's exchange
                // only after its correlated initialize result has reached stdout.
                session.handle(new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED, null)).block();
                initialized = true;
            }
        }
    }
}
