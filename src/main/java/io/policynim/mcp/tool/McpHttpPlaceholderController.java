package io.policynim.mcp.tool;

import io.policynim.config.McpTransport;
import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.transport.StreamableHttpTransportMarker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@ResponseBody
@RequestMapping("${policynim.mcp.streamable-http-path:/mcp}")
public class McpHttpPlaceholderController {

    private static final String PLACEHOLDER_REASON =
        "MCP HTTP transport is bootstrapped. Tool registration lands in later PRs.";

    private final PolicyNimProperties properties;
    private final StreamableHttpTransportMarker transportMarker;

    public McpHttpPlaceholderController(
        PolicyNimProperties properties,
        StreamableHttpTransportMarker transportMarker
    ) {
        this.properties = properties;
        this.transportMarker = transportMarker;
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<McpPlaceholderResponse> placeholder() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
            new McpPlaceholderResponse(
                properties.getMcp().getName(),
                McpTransport.STREAMABLE_HTTP.configValue(),
                transportMarker.path(),
                PLACEHOLDER_REASON
            )
        );
    }
}
