package io.policynim.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class PolicyMcpToolSpecificationConfiguration {

    @Bean
    List<McpServerFeatures.SyncToolSpecification> policySyncToolSpecifications(
        PolicyMcpTools tools,
        ObjectMapper objectMapper
    ) {
        return List.of(
            new McpServerFeatures.SyncToolSpecification(
                tool(
                    "policy_search",
                    "Search the local PolicyNIM policy corpus.",
                    "query",
                    Map.of(
                        "query", stringProperty("The policy search query."),
                        "domain", stringProperty("Optional policy domain filter."),
                        "topK", integerProperty("Maximum number of hits to return.")
                    )
                ),
                (exchange, arguments) -> toolResult(
                    objectMapper,
                    tools.policySearch(
                        requiredString(arguments, "query"),
                        optionalString(arguments, "domain"),
                        optionalInteger(arguments, "topK")
                    )
                )
            )
        );
    }

    private static McpSchema.Tool tool(
        String name,
        String description,
        String requiredProperty,
        Map<String, Object> properties
    ) {
        return McpSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                properties,
                List.of(requiredProperty),
                false,
                Map.of(),
                Map.of()
            ))
            .build();
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

    private static McpSchema.CallToolResult toolResult(ObjectMapper objectMapper, Object payload) {
        Map<String, Object> structuredContent = objectMapper.convertValue(
            payload,
            new TypeReference<>() {
            }
        );
        try {
            return McpSchema.CallToolResult.builder()
                .structuredContent(structuredContent)
                .addTextContent(objectMapper.writeValueAsString(structuredContent))
                .isError(false)
                .build();
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize MCP tool payload.", exception);
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
