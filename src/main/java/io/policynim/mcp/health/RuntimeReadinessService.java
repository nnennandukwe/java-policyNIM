package io.policynim.mcp.health;

public interface RuntimeReadinessService {

    HealthCheckResponse currentReadiness();

    default HealthCheckResponse toolReadiness() {
        return currentReadiness();
    }
}
