package io.policynim.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties(prefix = "policynim")
public class PolicyNimProperties {

    @Valid
    @NotNull
    private final McpProperties mcp = new McpProperties();

    @Valid
    @NotNull
    private final StorageProperties storage = new StorageProperties();

    public McpProperties getMcp() {
        return mcp;
    }

    public StorageProperties getStorage() {
        return storage;
    }

    public static final class McpProperties {

        @NotBlank
        private String name = "PolicyNIM";

        @NotNull
        private McpTransport transport = McpTransport.STREAMABLE_HTTP;

        @NotBlank
        private String host = "127.0.0.1";

        @Min(1)
        @Max(65535)
        private int port = 8080;

        @NotBlank
        @Pattern(regexp = "^/.*", message = "streamable-http-path must start with '/'")
        private String streamableHttpPath = "/mcp";

        private URI publicBaseUrl;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public McpTransport getTransport() {
            return transport;
        }

        public void setTransport(McpTransport transport) {
            this.transport = transport;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getStreamableHttpPath() {
            return streamableHttpPath;
        }

        public void setStreamableHttpPath(String streamableHttpPath) {
            this.streamableHttpPath = streamableHttpPath;
        }

        public URI getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(URI publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String mcpUrl() {
            if (publicBaseUrl == null) {
                return null;
            }
            String base = publicBaseUrl.toString().replaceAll("/+$", "");
            return base + streamableHttpPath;
        }
    }

    public static final class StorageProperties {

        @NotNull
        private StorageMode mode = StorageMode.NOOP;

        @NotBlank
        @Pattern(
            regexp = "^[A-Za-z_][A-Za-z0-9_]*$",
            message = "table-name must be a simple SQL identifier"
        )
        private String tableName = "policy_chunks";

        public StorageMode getMode() {
            return mode;
        }

        public void setMode(StorageMode mode) {
            this.mode = mode;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
    }

    public enum StorageMode {
        NOOP,
        JDBC
    }
}
