package io.policynim.mcp.health;

public record HealthCheckResponse(
    String status,
    boolean ready,
    String tableName,
    long rowCount,
    String mcpUrl,
    String reason
) {
}
