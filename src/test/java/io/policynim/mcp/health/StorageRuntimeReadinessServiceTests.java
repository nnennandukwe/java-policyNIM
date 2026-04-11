package io.policynim.mcp.health;

import io.policynim.config.PolicyNimProperties;
import io.policynim.retrieval.PolicyChunkReadStore;
import io.policynim.retrieval.ScoredPolicyChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StorageRuntimeReadinessServiceTests {

    @Test
    void reportsNoopStorageAsNotReadyWithRecoveryText() {
        PolicyNimProperties properties = new PolicyNimProperties();

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties,
            PolicyChunkReadStore.noOp()
        ).currentReadiness();

        assertThat(response.ready()).isFalse();
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.rowCount()).isZero();
        assertThat(response.reason())
            .contains("policy index is not wired yet")
            .contains("policynim.storage.mode=jdbc");
        assertThat(response.checks().get("storage").status()).isEqualTo("error");
    }

    @Test
    void reportsMissingJdbcTableAsNotReady() {
        PolicyNimProperties properties = jdbcProperties();

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties,
            new FakeReadStore(false, 0)
        ).currentReadiness();

        assertThat(response.ready()).isFalse();
        assertThat(response.reason())
            .contains("policy_chunks")
            .contains("Run Flyway migrations");
        assertThat(response.checks().get("storage").reason()).contains("Run Flyway migrations");
    }

    @Test
    void reportsEmptyJdbcTableAsNotReady() {
        PolicyNimProperties properties = jdbcProperties();

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties,
            new FakeReadStore(true, 0)
        ).currentReadiness();

        assertThat(response.ready()).isFalse();
        assertThat(response.rowCount()).isZero();
        assertThat(response.reason()).contains("Run the ingest command");
        assertThat(response.checks().get("storage").reason()).contains("Run the ingest command");
    }

    @Test
    void reportsJdbcTableWithRowsAsReady() {
        PolicyNimProperties properties = jdbcProperties();

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties,
            new FakeReadStore(true, 7)
        ).currentReadiness();

        assertThat(response.ready()).isTrue();
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.rowCount()).isEqualTo(7);
        assertThat(response.checks().get("storage").status()).isEqualTo("ok");
    }

    @Test
    void convertsReadinessStoreFailuresIntoFailClosedHealthPayloads() {
        PolicyNimProperties properties = jdbcProperties();

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties,
            new FailingReadStore()
        ).currentReadiness();

        assertThat(response.ready()).isFalse();
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.reason())
            .contains("Could not verify policy chunk table")
            .contains("Confirm database connectivity and migrations");
        assertThat(response.checks().get("storage").reason()).contains("IllegalStateException");
    }

    private static PolicyNimProperties jdbcProperties() {
        PolicyNimProperties properties = new PolicyNimProperties();
        properties.getStorage().setMode(PolicyNimProperties.StorageMode.JDBC);
        return properties;
    }

    private static final class FakeReadStore implements PolicyChunkReadStore {

        private final boolean exists;
        private final long rowCount;

        private FakeReadStore(boolean exists, long rowCount) {
            this.exists = exists;
            this.rowCount = rowCount;
        }

        @Override
        public boolean exists() {
            return exists;
        }

        @Override
        public long rowCount() {
            return rowCount;
        }

        @Override
        public List<ScoredPolicyChunk> search(String query, String domain, int limit) {
            return List.of();
        }
    }

    private static final class FailingReadStore implements PolicyChunkReadStore {

        @Override
        public boolean exists() {
            throw new IllegalStateException("database connection refused");
        }

        @Override
        public long rowCount() {
            return 0;
        }

        @Override
        public List<ScoredPolicyChunk> search(String query, String domain, int limit) {
            return List.of();
        }
    }
}
