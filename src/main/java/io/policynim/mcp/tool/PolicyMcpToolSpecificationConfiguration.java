package io.policynim.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Configuration(proxyBeanMethods = false)
public class PolicyMcpToolSpecificationConfiguration {

    private static final String INTERNAL_TOOL_ERROR_MESSAGE =
        "PolicyNIM tool invocation failed. See server logs for details.";

    private final ObjectMapper objectMapper;
    private final List<PolicyTool> definitions;

    PolicyMcpToolSpecificationConfiguration(PolicyMcpTools tools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.definitions = List.of(
            tool("policy_search", "Search the local PolicyNIM policy corpus.", "query",
                Map.of(
                    "query", stringProperty("The policy search query."),
                    "domain", stringProperty("Optional policy domain filter."),
                    "topK", integerProperty("Maximum number of hits to return.")
                ),
                arguments -> tools.policySearch(requiredString(arguments, "query"),
                    optionalString(arguments, "domain"), optionalInteger(arguments, "topK"))),
            tool("policy_preflight", "Run grounded PolicyNIM preflight for a coding task.", "task",
                Map.of(
                    "task", stringProperty("The coding task that needs policy guidance."),
                    "domain", stringProperty("Optional policy domain filter."),
                    "topK", integerProperty("Maximum number of evidence hits to use.")
                ),
                arguments -> tools.policyPreflight(requiredString(arguments, "task"),
                    optionalString(arguments, "domain"), optionalInteger(arguments, "topK")))
        );
    }

    @Bean
    List<McpServerFeatures.SyncToolSpecification> policySyncToolSpecifications() {
        return definitions.stream().map(tool -> new McpServerFeatures.SyncToolSpecification(tool.legacyDefinition(),
            (exchange, request) -> invokeTool(tool, request.arguments()).legacyResult())).toList();
    }

    @Bean
    List<McpStatelessServerFeatures.SyncToolSpecification> groundedLegacyTools() {
        return definitions.stream().map(tool -> new McpStatelessServerFeatures.SyncToolSpecification(tool.legacyDefinition(),
            (context, request) -> invokeTool(tool, request.arguments()).legacyResult())).toList();
    }

    @Bean
    McpSyncToolRepository groundedModernTools() {
        List<McpSyncTool> tools = definitions.stream().map(tool -> McpSyncTool.of(
            Tool.builder(tool.name(), tool.inputSchema()).description(tool.description()).build(),
            (context, request) -> McpSyncResponse.result(invokeTool(tool, request.arguments()).modernResult()))).toList();
        ToolsPage page = ToolsPage.of(tools.stream().map(McpSyncTool::definition).toList());
        return new McpSyncToolRepository() {
            @Override
            public ToolsPage list(McpRequestContext context, String cursor) {
                return page;
            }

            @Override
            public McpSyncTool find(McpRequestContext context, String name) {
                return tools.stream().filter(tool -> tool.definition().name().equals(name)).findFirst().orElse(null);
            }
        };
    }

    private static PolicyTool tool(
        String name,
        String description,
        String requiredProperty,
        Map<String, Object> properties,
        Function<Map<String, Object>, Object> invocation
    ) {
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", properties,
            "required", List.of(requiredProperty),
            "additionalProperties", false,
            "$defs", Map.of(),
            "definitions", Map.of()
        );
        return new PolicyTool(name, description, inputSchema, invocation);
    }

    private static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> integerProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("minimum", 1);
        property.put("maximum", 20);
        property.put("description", description);
        return property;
    }

    private record PolicyTool(String name, String description, Map<String, Object> inputSchema,
        Function<Map<String, Object>, Object> invocation) {

        McpSchema.Tool legacyDefinition() {
            return McpSchema.Tool.builder(name, inputSchema).description(description).build();
        }
    }

    private record ToolReply(Map<String, Object> structuredContent, String text, boolean isError) {

        McpSchema.CallToolResult legacyResult() {
            return McpSchema.CallToolResult.builder()
                .structuredContent(structuredContent)
                .addTextContent(text)
                .isError(isError)
                .build();
        }

        CallToolResult modernResult() {
            return CallToolResult.builder()
                .structuredContent(structuredContent)
                .addContent(TextContent.builder(text).build())
                .isError(isError)
                .build();
        }

        static ToolReply error(String message) {
            return new ToolReply(Map.of("error", message), message, true);
        }
    }

    private ToolReply invokeTool(PolicyTool tool, Map<String, Object> arguments) {
        try {
            Object payload = tool.invocation().apply(arguments == null ? Map.of() : arguments);
            Map<String, Object> content = objectMapper.convertValue(payload, new TypeReference<>() {});
            return new ToolReply(content, objectMapper.writeValueAsString(content), false);
        }
        catch (McpToolInvocationException | IllegalArgumentException exception) {
            return ToolReply.error(exception.getMessage());
        }
        catch (JsonProcessingException | RuntimeException exception) {
            return ToolReply.error(INTERNAL_TOOL_ERROR_MESSAGE);
        }
    }

    private static String requiredString(Map<String, Object> arguments, String key) {
        String value = optionalString(arguments, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private static Integer optionalInteger(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
