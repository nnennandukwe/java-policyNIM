package io.policynim.mcp.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class McpHttpPlaceholderControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsNotImplementedForGetRequests() throws Exception {
        mockMvc.perform(get("/mcp"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.server").value("PolicyNIM"))
            .andExpect(jsonPath("$.transport").value("streamable-http"))
            .andExpect(jsonPath("$.path").value("/mcp"));
    }

    @Test
    void returnsNotImplementedForPostRequests() throws Exception {
        mockMvc.perform(post("/mcp"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.reason").value("MCP HTTP transport is bootstrapped. Tool registration lands in later PRs."));
    }
}
