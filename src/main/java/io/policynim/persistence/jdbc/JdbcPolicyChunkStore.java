package io.policynim.persistence.jdbc;

import com.pgvector.PGvector;
import io.policynim.ingest.IngestedPolicyCorpus;
import io.policynim.ingest.PolicyChunkStore;
import io.policynim.policy.chunk.PolicyChunk;
import io.policynim.provider.EmbeddingInputType;
import io.policynim.provider.PolicyEmbeddingModel;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class JdbcPolicyChunkStore implements PolicyChunkStore {

    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;
    private final String tableName;
    private final String truncateSql;
    private final String insertSql;
    private final PolicyEmbeddingModel embeddingModel;

    JdbcPolicyChunkStore(
        JdbcTemplate jdbcTemplate,
        String tableName,
        TransactionOperations transactionOperations,
        PolicyEmbeddingModel embeddingModel
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionOperations = Objects.requireNonNull(
            transactionOperations,
            "transactionOperations must not be null"
        );
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        this.tableName = validateTableName(tableName);
        this.truncateSql = "TRUNCATE TABLE " + this.tableName;
        this.insertSql =
            "INSERT INTO " + this.tableName + " ("
                + "chunk_id, source_path, section_path, line_range, chunk_text, "
                + "policy_id, title, doc_type, domain, tags, grounded_in, embedding"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    @Override
    public void replaceAll(IngestedPolicyCorpus corpus) {
        Objects.requireNonNull(corpus, "corpus must not be null");
        transactionOperations.executeWithoutResult(status -> {
            jdbcTemplate.execute(truncateSql);

            List<PolicyChunk> chunks = corpus.chunks();
            if (chunks.isEmpty()) {
                return;
            }
            List<float[]> embeddings = embeddingModel.embed(
                chunks.stream().map(PolicyChunk::text).toList(),
                EmbeddingInputType.PASSAGE
            );

            jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    PolicyChunk chunk = chunks.get(index);
                    statement.setString(1, chunk.chunkId());
                    statement.setString(2, chunk.path());
                    statement.setString(3, chunk.section());
                    statement.setString(4, chunk.lines());
                    statement.setString(5, chunk.text());
                    statement.setString(6, chunk.policy().policyId());
                    statement.setString(7, chunk.policy().title());
                    statement.setString(8, chunk.policy().docType());
                    statement.setString(9, chunk.policy().domain());
                    statement.setArray(10, textArray(statement, chunk.policy().tags()));
                    statement.setArray(11, textArray(statement, chunk.policy().groundedIn()));
                    if (index < embeddings.size()) {
                        statement.setObject(12, new PGvector(embeddings.get(index)));
                    }
                    else {
                        statement.setNull(12, Types.OTHER);
                    }
                }

                @Override
                public int getBatchSize() {
                    return chunks.size();
                }
            });
        });
    }

    private static Array textArray(PreparedStatement statement, List<String> values) throws SQLException {
        return statement.getConnection().createArrayOf("text", values.toArray(String[]::new));
    }

    private static String validateTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        if (!SIMPLE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName must be a simple SQL identifier");
        }
        return tableName;
    }
}
