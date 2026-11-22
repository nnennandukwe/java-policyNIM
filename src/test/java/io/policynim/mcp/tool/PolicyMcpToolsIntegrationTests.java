package io.policynim.mcp.tool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyMcpToolsIntegrationTests {

    @LocalServerPort
    private int port;

    @Test
    void discoversBothToolsAndPreservesSearchErrorsThroughTheDefaultSession() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            client.initialize();
            assertThat(client.listTools().tools())
                .extracting(McpSchema.Tool::name)
                .containsExactly("policy_search", "policy_preflight");

            var missingQuery = client.callTool(McpSchema.CallToolRequest.builder("policy_search")
                .arguments(Map.of())
                .build());
            assertThat(errorMessage(missingQuery)).isEqualTo("query must not be blank");

            Map<String, Object> arguments = new HashMap<>();
            arguments.put("query", "background cleanup");
            arguments.put("topK", null);
            var notReady = client.callTool(McpSchema.CallToolRequest.builder("policy_search")
                .arguments(arguments)
                .build());
            assertThat(errorMessage(notReady)).contains("policy_search is unavailable", "ingest");
        }
    }

    private static String errorMessage(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        String message = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertThat(result.structuredContent()).isEqualTo(Map.of("error", message));
        return message;
    }
}
