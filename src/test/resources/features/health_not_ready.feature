Feature: Runtime health
  Scenario: The bootstrap health endpoint is not ready yet
    Given the Spring application context is running
    When I request the health endpoint
    Then the health response status is 503
    And the health response reason contains "policy index is not wired yet"
