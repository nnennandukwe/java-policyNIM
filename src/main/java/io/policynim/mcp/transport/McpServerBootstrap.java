package io.policynim.mcp.transport;

public interface McpServerBootstrap {

    McpTransport transport();

    String description();
}
