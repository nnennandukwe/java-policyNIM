package io.policynim.mcp.health;

import java.util.Map;

public record HealthCheckResponse(
    String status,
    boolean ready,
    String tableName,
    long rowCount,
    String mcpUrl,
    String reason,
    Map<String, HealthCheckDetail> checks
) {

    public HealthCheckResponse {
        checks = Map.copyOf(checks);
    }

    public HealthCheckResponse(
        String status,
        boolean ready,
        String tableName,
        long rowCount,
        String mcpUrl,
        String reason
    ) {
        this(status, ready, tableName, rowCount, mcpUrl, reason, Map.of());
    }
}
