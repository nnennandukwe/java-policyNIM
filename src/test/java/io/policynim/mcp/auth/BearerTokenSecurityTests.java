package io.policynim.mcp.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "policynim.mcp.auth.enabled=true",
    "policynim.mcp.auth.bearer-token=test-token"
})
@AutoConfigureMockMvc
class BearerTokenSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void leavesHealthzPublicWhenBearerAuthIsEnabled() throws Exception {
        mockMvc.perform(get("/healthz"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsHostedMcpRequestsWithoutBearerToken() throws Exception {
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
    }

    @Test
    void rejectsHostedMcpRequestsWithWrongBearerToken() throws Exception {
        mockMvc.perform(post("/mcp")
                .header("Authorization", "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
    }

    @Test
    void acceptsConfiguredBearerTokenBeforeTheMcpEndpointHandlesTheRequestBody() throws Exception {
        mockMvc.perform(post("/mcp")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

}

@SpringBootTest(properties = "policynim.mcp.auth.enabled=false")
@AutoConfigureMockMvc
class BearerTokenDisabledSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void doesNotRequireBasicAuthWhenBearerAuthIsDisabled() throws Exception {
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }
}

@SpringBootTest(properties = {
    "policynim.mcp.transport=stdio",
    "policynim.mcp.auth.enabled=true",
    "policynim.mcp.auth.bearer-token= "
})
class StdioBearerTokenSecurityTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void startsStdioAuthEnabledWithoutABearerToken() {
        assertThat(applicationContext).isNotNull();
    }
}
