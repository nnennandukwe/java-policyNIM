package io.policynim.mcp.health;

import io.policynim.config.PolicyNimProperties;
import io.policynim.retrieval.PolicyChunkReadStore;

import java.util.Map;
import java.util.Objects;

final class StorageRuntimeReadinessService implements RuntimeReadinessService {

    private static final String STORAGE_CHECK = "storage";

    private final PolicyNimProperties properties;
    private final PolicyChunkReadStore readStore;

    StorageRuntimeReadinessService(PolicyNimProperties properties, PolicyChunkReadStore readStore) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.readStore = Objects.requireNonNull(readStore, "readStore must not be null");
    }

    @Override
    public HealthCheckResponse currentReadiness() {
        return computeReadiness(true);
    }

    @Override
    public HealthCheckResponse toolReadiness() {
        return computeReadiness(false);
    }

    private HealthCheckResponse computeReadiness(boolean includeExactRowCount) {
        if (properties.getStorage().getMode() != PolicyNimProperties.StorageMode.JDBC) {
            return notReady(
                0,
                "Bootstrap mode is active. The policy index is not wired yet. Configure "
                    + "policynim.storage.mode=jdbc, run Flyway migrations, and run the ingest command before "
                    + "serving MCP traffic."
            );
        }

        try {
            if (!includeExactRowCount) {
                if (readStore.hasRows()) {
                    return ready(1);
                }
                return notReady(
                    0,
                    "Policy chunk table " + tableName() + " is not available or has no ingested chunks. "
                        + "Run Flyway migrations and the ingest command before serving MCP traffic."
                );
            }

            if (!readStore.exists()) {
                return notReady(
                    0,
                    "Policy chunk table " + tableName()
                        + " is not available. Run Flyway migrations before serving MCP traffic."
                );
            }

            long rowCount = readStore.rowCount();
            if (rowCount == 0) {
                return notReady(
                    rowCount,
                    "Policy chunk table " + tableName()
                        + " has no ingested chunks. Run the ingest command before serving MCP traffic."
                );
            }

            return ready(rowCount);
        }
        catch (RuntimeException exception) {
            return notReady(
                0,
                "Could not verify policy chunk table " + tableName()
                    + ". Confirm database connectivity and migrations before serving MCP traffic.",
                "Readiness check failed with " + exception.getClass().getSimpleName() + "."
            );
        }
    }

    private HealthCheckResponse ready(long rowCount) {
        return new HealthCheckResponse(
            "ok",
            true,
            tableName(),
            rowCount,
            properties.getMcp().mcpUrl(),
            "Policy chunk index is ready.",
            checks(new HealthCheckDetail("ok", "Policy chunk table is queryable."))
        );
    }

    private HealthCheckResponse notReady(long rowCount, String reason) {
        return notReady(rowCount, reason, reason);
    }

    private HealthCheckResponse notReady(long rowCount, String reason, String checkReason) {
        return new HealthCheckResponse(
            "error",
            false,
            tableName(),
            rowCount,
            properties.getMcp().mcpUrl(),
            reason,
            checks(new HealthCheckDetail("error", checkReason))
        );
    }

    private Map<String, HealthCheckDetail> checks(HealthCheckDetail storageCheck) {
        return Map.of(STORAGE_CHECK, storageCheck);
    }

    private String tableName() {
        return properties.getStorage().getTableName();
    }
}
