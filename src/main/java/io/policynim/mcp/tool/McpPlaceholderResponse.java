package io.policynim.mcp.tool;

public record McpPlaceholderResponse(
    String server,
    String transport,
    String path,
    String reason
) {
}
