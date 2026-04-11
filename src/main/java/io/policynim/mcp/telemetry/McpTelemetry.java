package io.policynim.mcp.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public final class McpTelemetry {

    public static final String TOOL_INVOCATIONS = "policynim.mcp.tool.invocations";
    public static final String TOOL_DURATION = "policynim.mcp.tool.duration";

    private static final Logger LOGGER = LoggerFactory.getLogger(McpTelemetry.class);

    private final MeterRegistry meterRegistry;

    public McpTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    public <T> T recordToolInvocation(String toolName, Supplier<T> invocation) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(invocation, "invocation must not be null");
        long startedAt = System.nanoTime();
        try {
            T result = invocation.get();
            record(toolName, "success", startedAt, null);
            return result;
        }
        catch (RuntimeException exception) {
            record(toolName, "error", startedAt, exception);
            throw exception;
        }
    }

    private void record(String toolName, String outcome, long startedAt, RuntimeException exception) {
        meterRegistry.counter(TOOL_INVOCATIONS, "tool.name", toolName, "outcome", outcome).increment();
        Timer.builder(TOOL_DURATION)
            .tag("tool.name", toolName)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);

        if (exception == null) {
            LOGGER.atDebug()
                .addKeyValue("event", "mcp.tool.invocation")
                .addKeyValue("tool.name", toolName)
                .addKeyValue("outcome", outcome)
                .log("MCP tool invocation completed");
            return;
        }

        LOGGER.atWarn()
            .addKeyValue("event", "mcp.tool.invocation")
            .addKeyValue("tool.name", toolName)
            .addKeyValue("outcome", outcome)
            .addKeyValue("error.type", exception.getClass().getSimpleName())
            .setCause(exception)
            .log("MCP tool invocation failed");
    }
}
