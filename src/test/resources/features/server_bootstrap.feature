Feature: Server bootstrap
  Scenario: The default MCP bootstrap uses streamable HTTP
    Given the Spring application context is running
    When I inspect the active MCP bootstrap
    Then the active MCP bootstrap transport is "streamable-http"
