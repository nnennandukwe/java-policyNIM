package io.policynim.mcp.transport;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroundedHttpListenersTests {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledWriterReleasesCapacityIncludingBeforeItsFirstCallback(boolean started) throws Exception {
        var tick = new AtomicReference<Runnable>();
        var timer = mock(ScheduledExecutorService.class);
        doAnswer(call -> { tick.set(call.getArgument(0)); return null; })
            .when(timer).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        try (var listeners = new GroundedHttpListeners(1, timer)) {
            var original = request();
            var output = mock(ServletOutputStream.class);
            var callback = new AtomicReference<WriteListener>();
            doAnswer(call -> { callback.set(call.getArgument(0)); return null; }).when(output).setWriteListener(any());
            listeners.open(original, response(output));
            if (started) {
                when(output.isReady()).thenReturn(true);
                callback.get().onWritePossible();
                when(output.isReady()).thenReturn(false);
            }

            tick.get().run();
            assertThat(original.isAsyncStarted()).isTrue();
            tick.get().run();
            assertThat(original.isAsyncStarted()).isFalse();
            var replacement = request();
            listeners.open(replacement, response(output));
            when(output.isReady()).thenReturn(true);
            callback.get().onWritePossible();
            // A transient stall between successful writes doesn't end the replacement stream.
            for (int round = 0; round < 3; round++) {
                when(output.isReady()).thenReturn(false);
                tick.get().run();
                when(output.isReady()).thenReturn(true);
                tick.get().run();
            }
            assertThat(replacement.isAsyncStarted()).isTrue();
        }
    }

    @Test
    void setupCanReceiveAContainerCallbackAndReleaseOwnershipWhenRegistrationFails() throws Exception {
        try (var listeners = new GroundedHttpListeners(1)) {
            var request = request();
            var output = mock(ServletOutputStream.class);
            var callbackDone = new CountDownLatch(1);
            doAnswer(call -> {
                WriteListener listener = call.getArgument(0);
                Thread.startVirtualThread(() -> {
                    try {
                        listener.onError(new IOException("Connection ended during setup"));
                    }
                    finally {
                        callbackDone.countDown();
                    }
                });
                if (callbackDone.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Stream registration failed");
                }
                return null;
            }).when(output).setWriteListener(any());

            assertThatThrownBy(() -> listeners.open(request, response(output)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Stream registration failed");
            assertThat(request.isAsyncStarted()).isFalse();
            var retry = request();
            listeners.open(retry, response(mock(ServletOutputStream.class)));
            assertThat(retry.isAsyncStarted()).isTrue();
        }
    }

    @Test
    void completesContextWhenShutdownOccursDuringAsyncStartup() throws Exception {
        try (var listeners = new GroundedHttpListeners(1)) {
            var request = new MockHttpServletRequest("GET", "/policy") {
                @Override
                public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
                    listeners.close();
                    return super.startAsync(request, response);
                }
            };
            request.setAsyncSupported(true);
            listeners.open(request, response(mock(ServletOutputStream.class)));
            assertThat(request.isAsyncStarted()).isFalse();
            var rejected = new MockHttpServletResponse();
            listeners.open(request(), rejected);
            assertThat(rejected.getStatus()).isEqualTo(503);
        }
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/policy");
        request.setAsyncSupported(true);
        return request;
    }

    private static MockHttpServletResponse response(ServletOutputStream output) {
        return new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return output;
            }
        };
    }
}
