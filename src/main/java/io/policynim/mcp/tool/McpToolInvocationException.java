package io.policynim.mcp.tool;

public class McpToolInvocationException extends RuntimeException {

    public McpToolInvocationException(String message) {
        super(message);
    }

    public McpToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
