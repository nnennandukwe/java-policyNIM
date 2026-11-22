package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacySseServletTests {

    @Test
    void shutdownWaitsForTheSdkToRegisterAnOpeningStream() throws Exception {
        var servlet = new LegacySseServlet(HttpServletSseServerTransportProvider.builder()
            .messageEndpoint("/mcp/message").build(), "/sse", "/mcp/message");
        var connected = new MockHttpServletRequest("GET", "/sse");
        connected.setAsyncSupported(true);
        var headersFlushed = new CountDownLatch(1);
        var continueSetup = new CountDownLatch(1);
        var response = new MockHttpServletResponse() {
            @Override
            public void flushBuffer() {
                super.flushBuffer();
                headersFlushed.countDown();
                try {
                    assertThat(continueSetup.await(5, TimeUnit.SECONDS)).isTrue();
                }
                catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
            }
        };
        var server = McpServer.sync(servlet.transport()).build();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var get = executor.submit(() -> {
                servlet.service(connected, response);
                return null;
            });
            try {
                assertThat(headersFlushed.await(5, TimeUnit.SECONDS)).isTrue();
                var close = executor.submit(servlet::destroy);
                assertThatThrownBy(() -> close.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                continueSetup.countDown();
                get.get(5, TimeUnit.SECONDS);
                close.get(5, TimeUnit.SECONDS);
                assertThat(connected.isAsyncStarted()).isFalse();
                var late = new MockHttpServletResponse();
                servlet.service(new MockHttpServletRequest("GET", "/sse"), late);
                assertThat(late.getStatus()).isEqualTo(503);
            }
            finally {
                continueSetup.countDown();
            }
        }
        finally {
            server.closeGracefully();
        }
    }
}
