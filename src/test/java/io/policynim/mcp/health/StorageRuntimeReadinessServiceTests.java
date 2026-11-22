package io.policynim.mcp.health;

import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.transport.McpBootstrapConfiguration;
import io.policynim.retrieval.PolicyChunkReadStore;
import io.policynim.retrieval.ScoredPolicyChunk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StorageRuntimeReadinessServiceTests {

    @Test
    void reportsTheSelectedHttpEndpointUnderThePublicBasePath() {
        PolicyNimProperties properties = new PolicyNimProperties();
        properties.getMcp().setPublicBaseUrl(URI.create("https://parity.invalid/platform/"));
        properties.getMcp().setStreamableHttpPath("/fallback");
        new ApplicationContextRunner()
            .withBean(PolicyNimProperties.class, () -> properties)
            .withUserConfiguration(McpBootstrapConfiguration.class, RuntimeReadinessConfiguration.class)
            .withPropertyValues("spring.ai.mcp.server.streamable-http.mcp-endpoint=/selected-policies")
            .run(context -> assertThat(context.getBean(RuntimeReadinessService.class).currentReadiness().mcpUrl())
                .isEqualTo("https://parity.invalid/platform/selected-policies"));
    }

    @Test
    void reportsNoopStorageAsNotReadyWithRecoveryText() {
        PolicyNimProperties properties = new PolicyNimProperties();

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties.getStorage(), PolicyChunkReadStore.noOp(), null
        ).currentReadiness();

        assertThat(response.ready()).isFalse();
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.rowCount()).isZero();
        assertThat(response.mcpUrl()).isNull();
        assertThat(response.reason())
            .contains("policy index is not wired yet")
            .contains("policynim.storage.mode=jdbc");
        assertThat(response.checks().get("storage").status()).isEqualTo("error");
    }

    @Test
    void reportsMissingJdbcTableAsNotReady() {
        PolicyNimProperties properties = jdbcProperties();

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties.getStorage(), new FakeReadStore(false, 0), null
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
            properties.getStorage(), new FakeReadStore(true, 0), null
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
            properties.getStorage(), new FakeReadStore(true, 7), "https://parity.invalid/platform/selected-policies"
        ).currentReadiness();

        assertThat(response.ready()).isTrue();
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.rowCount()).isEqualTo(7);
        assertThat(response.mcpUrl()).isEqualTo("https://parity.invalid/platform/selected-policies");
        assertThat(response.checks().get("storage").status()).isEqualTo("ok");
    }

    @Test
    void toolReadinessUsesCheapRowPresenceCheckWithoutCountingRows() {
        PolicyNimProperties properties = jdbcProperties();
        CountingReadStore readStore = new CountingReadStore(true, true, 7);

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties.getStorage(), readStore, null
        ).toolReadiness();

        assertThat(response.ready()).isTrue();
        assertThat(readStore.existsCalls).isEqualTo(1);
        assertThat(readStore.hasRowsCalls).isEqualTo(1);
        assertThat(readStore.rowCountCalls).isZero();
    }

    @Test
    void convertsReadinessStoreFailuresIntoFailClosedHealthPayloads() {
        PolicyNimProperties properties = jdbcProperties();

        HealthCheckResponse response = new StorageRuntimeReadinessService(
            properties.getStorage(), new FailingReadStore(), null
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
        public boolean hasRows() {
            return exists && rowCount > 0;
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

    private static final class CountingReadStore implements PolicyChunkReadStore {

        private final boolean exists;
        private final boolean hasRows;
        private final long rowCount;
        private int existsCalls;
        private int hasRowsCalls;
        private int rowCountCalls;

        private CountingReadStore(boolean exists, boolean hasRows, long rowCount) {
            this.exists = exists;
            this.hasRows = hasRows;
            this.rowCount = rowCount;
        }

        @Override
        public boolean exists() {
            existsCalls++;
            return exists;
        }

        @Override
        public long rowCount() {
            rowCountCalls++;
            return rowCount;
        }

        @Override
        public boolean hasRows() {
            hasRowsCalls++;
            return exists() && hasRows;
        }

        @Override
        public List<ScoredPolicyChunk> search(String query, String domain, int limit) {
            return List.of();
        }
    }
}
