package io.policynim.persistence.jdbc;

import com.pgvector.PGvector;
import io.policynim.ingest.IngestCommand;
import io.policynim.ingest.IngestService;
import io.policynim.provider.EmbeddingInputType;
import io.policynim.provider.PolicyEmbeddingModel;
import io.policynim.support.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "policynim.storage.mode=jdbc")
@Import(PostgresTestContainerConfiguration.class)
class JdbcPolicyChunkStoreIT {

    private static final String TABLE_NAME = "policy_chunks";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IngestService ingestService;

    @TempDir
    Path tempDir;

    @Test
    void flywayCreatesPgvectorBackedStorage() {
        assertThat(jdbcTemplate.queryForObject(
            "select extname from pg_extension where extname = 'vector'",
            String.class
        )).isEqualTo("vector");

        assertThat(jdbcTemplate.queryForObject(
            """
            select format_type(attribute.atttypid, attribute.atttypmod)
            from pg_attribute attribute
            join pg_class table_class on table_class.oid = attribute.attrelid
            join pg_namespace namespace on namespace.oid = table_class.relnamespace
            where namespace.nspname = current_schema()
              and table_class.relname = ?
              and attribute.attname = 'embedding'
              and attribute.attnum > 0
              and not attribute.attisdropped
            """,
            String.class,
            TABLE_NAME
        )).isEqualTo("vector");
    }

    @Test
    void pgvectorColumnAcceptsJavaVectorValues() throws IOException {
        Path policiesDir = tempDir.resolve("policies");
        writePolicy(
            policiesDir.resolve("backend/logging.md"),
            """
                ---
                policy_id: BACKEND-LOG-001
                ---
                # Logging
                """
        );

        ingestService.ingest(new IngestCommand(policiesDir));
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                "update %s set embedding = ? where chunk_id = ?".formatted(TABLE_NAME)
            );
            statement.setObject(1, new PGvector(new float[] {1.0f, 2.0f, 3.0f}));
            statement.setString(2, "BACKEND-LOG-001:logging");
            return statement;
        });

        assertThat(jdbcTemplate.queryForObject(
            "select embedding::text from %s where chunk_id = ?".formatted(TABLE_NAME),
            String.class,
            "BACKEND-LOG-001:logging"
        )).isEqualTo("[1,2,3]");
    }

    @Test
    void ingestsPoliciesWithEmbeddingsWhenAnEmbeddingModelIsConfigured() throws IOException {
        Path policiesDir = tempDir.resolve("policies");
        writePolicy(
            policiesDir.resolve("backend/logging.md"),
            """
                ---
                policy_id: BACKEND-LOG-001
                ---
                # Logging

                Add request ids.
                """
        );

        ingestService.ingest(new IngestCommand(policiesDir));

        assertThat(jdbcTemplate.queryForObject(
            "select embedding::text from %s where chunk_id = ?".formatted(TABLE_NAME),
            String.class,
            "BACKEND-LOG-001:logging"
        )).isEqualTo("[11,12,13]");
    }

    @Test
    void ingestsPoliciesIntoPostgresWithChunkMetadata() throws IOException {
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
                  - audit
                grounded_in:
                  - SOC2-CC7
                ---
                # Logging

                ## Rules

                Log with context.
                """
        );

        ingestService.ingest(new IngestCommand(policiesDir));

        assertThat(loadStoredChunks()).containsExactly(
            new StoredChunkRow(
                "BACKEND-LOG-001:logging",
                "policies/backend/logging.md",
                "Logging",
                "11-12",
                """
                # Logging
                """.trim(),
                "BACKEND-LOG-001",
                "Logging",
                "guidance",
                "backend",
                List.of("observability", "audit"),
                List.of("SOC2-CC7"),
                false
            ),
            new StoredChunkRow(
                "BACKEND-LOG-001:logging__rules",
                "policies/backend/logging.md",
                "Logging > Rules",
                "13-15",
                """
                ## Rules

                Log with context.
                """.trim(),
                "BACKEND-LOG-001",
                "Logging",
                "guidance",
                "backend",
                List.of("observability", "audit"),
                List.of("SOC2-CC7"),
                false
            )
        );
    }

    @Test
    void reingestReplacesPreviouslyStoredChunks() throws IOException {
        Path firstPoliciesDir = tempDir.resolve("first/policies");
        writePolicy(
            firstPoliciesDir.resolve("backend/logging.md"),
            """
                ---
                policy_id: BACKEND-LOG-001
                ---
                # Logging

                Log with context.
                """
        );
        Path secondPoliciesDir = tempDir.resolve("second/policies");
        writePolicy(
            secondPoliciesDir.resolve("security/sessions.md"),
            """
                ---
                policy_id: SECURITY-SESSION-001
                ---
                # Sessions

                Rotate session tokens.
                """
        );

        ingestService.ingest(new IngestCommand(firstPoliciesDir));
        ingestService.ingest(new IngestCommand(secondPoliciesDir));

        assertThat(jdbcTemplate.queryForList(
            "select chunk_id from %s order by chunk_id".formatted(TABLE_NAME),
            String.class
        )).containsExactly("SECURITY-SESSION-001:sessions");
    }

    private List<StoredChunkRow> loadStoredChunks() {
        return jdbcTemplate.query(
            """
            select chunk_id,
                   source_path,
                   section_path,
                   line_range,
                   chunk_text,
                   policy_id,
                   title,
                   doc_type,
                   domain,
                   tags,
                   grounded_in,
                   embedding is null as embedding_is_null
            from policy_chunks
            order by chunk_id
            """,
            JdbcPolicyChunkStoreIT::mapRow
        );
    }

    private static StoredChunkRow mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new StoredChunkRow(
            resultSet.getString("chunk_id"),
            resultSet.getString("source_path"),
            resultSet.getString("section_path"),
            resultSet.getString("line_range"),
            resultSet.getString("chunk_text"),
            resultSet.getString("policy_id"),
            resultSet.getString("title"),
            resultSet.getString("doc_type"),
            resultSet.getString("domain"),
            arrayValue(resultSet.getArray("tags")),
            arrayValue(resultSet.getArray("grounded_in")),
            resultSet.getBoolean("embedding_is_null")
        );
    }

    private static List<String> arrayValue(Array value) throws SQLException {
        if (value == null) {
            return List.of();
        }
        return List.of((String[]) value.getArray());
    }

    private static void writePolicy(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.stripLeading());
    }

    private record StoredChunkRow(
        String chunkId,
        String sourcePath,
        String sectionPath,
        String lineRange,
        String chunkText,
        String policyId,
        String title,
        String docType,
        String domain,
        List<String> tags,
        List<String> groundedIn,
        boolean embeddingIsNull
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EmbeddingTestConfiguration {

        @Bean
        PolicyEmbeddingModel policyEmbeddingModel() {
            return new PolicyEmbeddingModel() {
                @Override
                public List<float[]> embed(List<String> inputs, EmbeddingInputType inputType) {
                    return inputs.stream()
                        .map(ignored -> new float[] {11.0f, 12.0f, 13.0f})
                        .toList();
                }
            };
        }
    }
}
