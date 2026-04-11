package io.policynim.mcp.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthzControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RuntimeReadinessService runtimeReadinessService;

    @Test
    void returnsServiceUnavailableWhenRuntimeIsNotReady() throws Exception {
        given(runtimeReadinessService.currentReadiness()).willReturn(
            new HealthCheckResponse(
                "error",
                false,
                "policy_chunks",
                0,
                null,
                "Bootstrap mode is active. The policy index is not wired yet.",
                Map.of(
                    "storage",
                    new HealthCheckDetail("error", "Run Flyway migrations and ingest policies.")
                )
            )
        );

        mockMvc.perform(get("/healthz"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.ready").value(false))
            .andExpect(jsonPath("$.tableName").value("policy_chunks"))
            .andExpect(jsonPath("$.reason").value("Bootstrap mode is active. The policy index is not wired yet."))
            .andExpect(jsonPath("$.checks.storage.status").value("error"))
            .andExpect(jsonPath("$.checks.storage.reason").value("Run Flyway migrations and ingest policies."));
    }
}
