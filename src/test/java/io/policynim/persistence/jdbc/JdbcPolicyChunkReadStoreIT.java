package io.policynim.persistence.jdbc;

import com.pgvector.PGvector;
import io.policynim.ingest.IngestCommand;
import io.policynim.ingest.IngestService;
import io.policynim.retrieval.PolicyChunkReadStore;
import io.policynim.retrieval.ScoredPolicyChunk;
import io.policynim.support.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "policynim.storage.mode=jdbc")
@Import(PostgresTestContainerConfiguration.class)
class JdbcPolicyChunkReadStoreIT {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private PolicyChunkReadStore readStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    @Test
    void reportsTableStateAndSearchesStoredChunks() throws IOException {
        Path policiesDir = tempDir.resolve("policies");
        writePolicy(
            policiesDir.resolve("backend/logging.md"),
            """
                ---
                policy_id: BACKEND-LOG-001
                title: Logging
                domain: backend
                tags:
                  - observability
                ---
                # Logging

                ## Rules

                Add request ids to every log statement.
                """
        );
        writePolicy(
            policiesDir.resolve("security/sessions.md"),
            """
                ---
                policy_id: SECURITY-SESSION-001
                title: Sessions
                domain: security
                tags:
                  - auth
                ---
                # Sessions

                Rotate session tokens after privilege changes.
                """
        );

        assertThat(readStore.exists()).isTrue();
        assertThat(readStore.rowCount()).isZero();

        ingestService.ingest(new IngestCommand(policiesDir));

        List<ScoredPolicyChunk> results = readStore.search("logging request ids", "backend", 3);

        assertThat(readStore.rowCount()).isEqualTo(3);
        assertThat(results).extracting(ScoredPolicyChunk::chunkId).containsExactly(
            "BACKEND-LOG-001:logging__rules",
            "BACKEND-LOG-001:logging"
        );
        assertThat(results).allSatisfy(hit -> assertThat(hit.policy().domain()).isEqualTo("backend"));
        assertThat(results.getFirst().score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void findsNearestChunksByStoredEmbeddings() throws IOException {
        Path policiesDir = tempDir.resolve("policies");
        writePolicy(
            policiesDir.resolve("backend/logging.md"),
            """
                ---
                policy_id: BACKEND-LOG-001
                title: Logging
                domain: backend
                ---
                # Logging

                Add request ids to every log statement.
                """
        );
        writePolicy(
            policiesDir.resolve("backend/tracing.md"),
            """
                ---
                policy_id: BACKEND-TRACE-001
                title: Tracing
                domain: backend
                ---
                # Tracing

                Trace downstream calls.
                """
        );

        ingestService.ingest(new IngestCommand(policiesDir));
        updateEmbedding("BACKEND-LOG-001:logging", new float[] {1.0f, 0.0f, 0.0f});
        updateEmbedding("BACKEND-TRACE-001:tracing", new float[] {0.0f, 1.0f, 0.0f});

        List<ScoredPolicyChunk> results = readStore.searchByEmbedding(new float[] {1.0f, 0.0f, 0.0f}, "backend", 2);

        assertThat(results).extracting(ScoredPolicyChunk::chunkId).containsExactly(
            "BACKEND-LOG-001:logging",
            "BACKEND-TRACE-001:tracing"
        );
        assertThat(results.getFirst().score()).isGreaterThan(results.get(1).score());
    }

    private static void writePolicy(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.stripLeading());
    }

    private void updateEmbedding(String chunkId, float[] embedding) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                "update policy_chunks set embedding = ? where chunk_id = ?"
            );
            statement.setObject(1, new PGvector(embedding));
            statement.setString(2, chunkId);
            return statement;
        });
    }
}
