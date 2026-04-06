package io.policynim.mcp.transport;

import io.policynim.config.McpTransport;

public interface McpServerBootstrap {

    McpTransport transport();

    String description();
}
