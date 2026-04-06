package io.policynim.acceptance;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.policynim.mcp.transport.McpServerBootstrap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public class BootstrapStepDefinitions {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    private McpServerBootstrap bootstrap;
    private MvcResult mvcResult;

    @Given("the Spring application context is running")
    public void theSpringApplicationContextIsRunning() {
        assertThat(applicationContext).isNotNull();
    }

    @When("I inspect the active MCP bootstrap")
    public void iInspectTheActiveMcpBootstrap() {
        bootstrap = applicationContext.getBean(McpServerBootstrap.class);
    }

    @Then("the active MCP bootstrap transport is {string}")
    public void theActiveMcpBootstrapTransportIs(String transport) {
        assertThat(bootstrap.transport().configValue()).isEqualTo(transport);
    }

    @When("I request the health endpoint")
    public void iRequestTheHealthEndpoint() throws Exception {
        mvcResult = mockMvc.perform(get("/healthz")).andReturn();
    }

    @Then("the health response status is {int}")
    public void theHealthResponseStatusIs(int status) {
        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(status);
    }

    @Then("the health response reason contains {string}")
    public void theHealthResponseReasonContains(String text) throws Exception {
        assertThat(mvcResult.getResponse().getContentAsString()).contains(text);
    }
}
