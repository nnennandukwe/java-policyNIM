package io.policynim.mcp.health;

import io.policynim.config.PolicyNimProperties;

final class BootstrapRuntimeReadinessService implements RuntimeReadinessService {

    private static final String DEFAULT_REASON =
        "Bootstrap mode is active. The policy index is not wired yet.";

    private final PolicyNimProperties properties;

    BootstrapRuntimeReadinessService(PolicyNimProperties properties) {
        this.properties = properties;
    }

    @Override
    public HealthCheckResponse currentReadiness() {
        return new HealthCheckResponse(
            "error",
            false,
            properties.getStorage().getTableName(),
            0,
            properties.getMcp().mcpUrl(),
            DEFAULT_REASON
        );
    }
}
