package io.policynim.mcp.tool;

import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "policynim.mcp.transport", havingValue = "streamable-http", matchIfMissing = true)
@RequestMapping("${policynim.mcp.streamable-http-path:/mcp}")
public class McpHttpPlaceholderController {

    private static final String PLACEHOLDER_REASON =
        "MCP HTTP transport is bootstrapped. Tool registration lands in later PRs.";

    private final PolicyNimProperties properties;

    public McpHttpPlaceholderController(PolicyNimProperties properties) {
        this.properties = properties;
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<McpPlaceholderResponse> placeholder() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
            new McpPlaceholderResponse(
                properties.getMcp().getName(),
                McpTransport.STREAMABLE_HTTP.configValue(),
                properties.getMcp().getStreamableHttpPath(),
                PLACEHOLDER_REASON
            )
        );
    }
}
