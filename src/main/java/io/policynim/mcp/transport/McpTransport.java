package io.policynim.mcp.transport;

public enum McpTransport {
    STDIO("stdio"),
    STREAMABLE_HTTP("streamable-http");

    private final String configValue;

    McpTransport(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }
}
