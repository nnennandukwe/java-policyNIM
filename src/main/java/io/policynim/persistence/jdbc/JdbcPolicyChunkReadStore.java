package io.policynim.persistence.jdbc;

import io.policynim.policy.model.PolicyMetadata;
import io.policynim.retrieval.PolicyChunkReadStore;
import io.policynim.retrieval.ScoredPolicyChunk;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JdbcPolicyChunkReadStore implements PolicyChunkReadStore {

    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9]+");
    private static final int MIN_CANDIDATE_POOL = 25;

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;
    private final String rowCountSql;
    private final String hasRowsSql;

    JdbcPolicyChunkReadStore(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.tableName = validateTableName(tableName);
        this.rowCountSql = "SELECT COUNT(*) FROM " + this.tableName;
        this.hasRowsSql = "SELECT EXISTS (SELECT 1 FROM " + this.tableName + " LIMIT 1)";
    }

    @Override
    public boolean exists() {
        Boolean exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name = ?
            )
            """,
            Boolean.class,
            tableName
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public long rowCount() {
        Long count = jdbcTemplate.queryForObject(rowCountSql, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public boolean hasRows() {
        if (!exists()) {
            return false;
        }
        Boolean hasRows = jdbcTemplate.queryForObject(hasRowsSql, Boolean.class);
        return Boolean.TRUE.equals(hasRows);
    }

    @Override
    public List<ScoredPolicyChunk> search(String query, String domain, int limit) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank() || limit <= 0 || !exists()) {
            return List.of();
        }

        List<String> tokens = tokenize(query);
        String sql = searchSql(tokens, domain != null);
        List<Object> parameters = buildSearchParameters(tokens, domain, Math.max(limit * 8, MIN_CANDIDATE_POOL));
        List<ScoredPolicyChunk> candidates = jdbcTemplate.query(
            sql,
            JdbcPolicyChunkReadStore::mapRow,
            parameters.toArray()
        );

        return candidates.stream()
            .map(chunk -> new ScoredPolicyChunk(
                chunk.chunkId(),
                chunk.path(),
                chunk.section(),
                chunk.lines(),
                chunk.text(),
                chunk.policy(),
                score(chunk, query, tokens, domain)
            ))
            .filter(chunk -> chunk.score() > 0.0d)
            .sorted(Comparator.comparingDouble(ScoredPolicyChunk::score).reversed()
                .thenComparing(ScoredPolicyChunk::chunkId))
            .limit(limit)
            .toList();
    }

    @Override
    public List<ScoredPolicyChunk> searchByEmbedding(float[] embedding, String domain, int limit) {
        Objects.requireNonNull(embedding, "embedding must not be null");
        if (embedding.length == 0 || limit <= 0 || !exists()) {
            return List.of();
        }

        return jdbcTemplate.query(
            vectorSearchSql(domain != null),
            JdbcPolicyChunkReadStore::mapVectorRow,
            vectorSearchParameters(embedding, domain, limit).toArray()
        );
    }

    private static String searchSqlTemplate(List<String> tokens, boolean domainFiltered) {
        StringBuilder sql = new StringBuilder(
            """
            SELECT chunk_id,
                   source_path,
                   section_path,
                   line_range,
                   chunk_text,
                   policy_id,
                   title,
                   doc_type,
                   domain,
                   tags,
                   grounded_in
            FROM """
        ).append(" %s").append(" WHERE 1=1");

        if (domainFiltered) {
            sql.append(" AND domain = ?");
        }
        if (!tokens.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append(
                    """
                    LOWER(policy_id) LIKE ?
                    OR LOWER(title) LIKE ?
                    OR LOWER(section_path) LIKE ?
                    OR LOWER(source_path) LIKE ?
                    OR LOWER(chunk_text) LIKE ?
                    OR EXISTS (SELECT 1 FROM unnest(tags) tag WHERE LOWER(tag) LIKE ?)
                    OR EXISTS (SELECT 1 FROM unnest(grounded_in) source WHERE LOWER(source) LIKE ?)
                    """
                );
            }
            sql.append(')');
        }
        sql.append(" ORDER BY created_at DESC, chunk_id ASC LIMIT ?");
        return sql.toString();
    }

    private List<Object> buildSearchParameters(List<String> tokens, String domain, int candidatePool) {
        List<Object> parameters = new ArrayList<>();
        if (domain != null) {
            parameters.add(domain);
        }
        for (String token : tokens) {
            String pattern = "%" + token + "%";
            for (int i = 0; i < 7; i++) {
                parameters.add(pattern);
            }
        }
        parameters.add(candidatePool);
        return parameters;
    }

    private static List<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        if (tokens.isEmpty()) {
            return List.of(query.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(tokens);
    }

    private static double score(ScoredPolicyChunk chunk, String query, List<String> tokens, String requestedDomain) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String policyId = chunk.policy().policyId().toLowerCase(Locale.ROOT);
        String title = chunk.policy().title().toLowerCase(Locale.ROOT);
        String section = chunk.section().toLowerCase(Locale.ROOT);
        String path = chunk.path().toLowerCase(Locale.ROOT);
        String text = chunk.text().toLowerCase(Locale.ROOT);

        double score = 0.0d;
        score += contains(policyId, normalizedQuery, 12.0d);
        score += contains(title, normalizedQuery, 10.0d);
        score += contains(section, normalizedQuery, 8.0d);
        score += contains(text, normalizedQuery, 9.0d);
        score += contains(path, normalizedQuery, 4.0d);
        if (requestedDomain != null && requestedDomain.equalsIgnoreCase(chunk.policy().domain())) {
            score += 2.0d;
        }

        for (String token : tokens) {
            score += contains(policyId, token, 5.0d);
            score += contains(title, token, 4.0d);
            score += contains(section, token, 3.0d);
            score += contains(path, token, 2.0d);
            score += contains(text, token, 2.0d);
            score += containsAny(chunk.policy().tags(), token, 2.0d);
            score += containsAny(chunk.policy().groundedIn(), token, 1.0d);
        }
        return score;
    }

    private static double contains(String value, String needle, double points) {
        return value.contains(needle) ? points : 0.0d;
    }

    private static double containsAny(List<String> values, String needle, double points) {
        return values.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains(needle))
            ? points
            : 0.0d;
    }

    private static ScoredPolicyChunk mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new ScoredPolicyChunk(
            resultSet.getString("chunk_id"),
            resultSet.getString("source_path"),
            resultSet.getString("section_path"),
            resultSet.getString("line_range"),
            resultSet.getString("chunk_text"),
            new PolicyMetadata(
                resultSet.getString("policy_id"),
                resultSet.getString("title"),
                resultSet.getString("doc_type"),
                resultSet.getString("domain"),
                arrayValue(resultSet.getArray("tags")),
                arrayValue(resultSet.getArray("grounded_in"))
            ),
            0.0d
        );
    }

    private static ScoredPolicyChunk mapVectorRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new ScoredPolicyChunk(
            resultSet.getString("chunk_id"),
            resultSet.getString("source_path"),
            resultSet.getString("section_path"),
            resultSet.getString("line_range"),
            resultSet.getString("chunk_text"),
            new PolicyMetadata(
                resultSet.getString("policy_id"),
                resultSet.getString("title"),
                resultSet.getString("doc_type"),
                resultSet.getString("domain"),
                arrayValue(resultSet.getArray("tags")),
                arrayValue(resultSet.getArray("grounded_in"))
            ),
            resultSet.getDouble("score")
        );
    }

    private static List<String> arrayValue(Array value) throws SQLException {
        if (value == null) {
            return List.of();
        }
        return List.of((String[]) value.getArray());
    }

    private static String validateTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        if (!SIMPLE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName must be a simple SQL identifier");
        }
        return tableName;
    }

    private String searchSql(List<String> tokens, boolean domainFiltered) {
        return searchSqlTemplate(tokens, domainFiltered).formatted(tableName);
    }

    private String vectorSearchSql(boolean domainFiltered) {
        StringBuilder sql = new StringBuilder(
            """
            SELECT chunk_id,
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
                   1 - (embedding <=> CAST(? AS vector)) AS score
            FROM
            """
        ).append(' ').append(tableName).append(" WHERE embedding IS NOT NULL");

        if (domainFiltered) {
            sql.append(" AND domain = ?");
        }

        sql.append(" ORDER BY embedding <=> CAST(? AS vector), created_at DESC, chunk_id ASC LIMIT ?");
        return sql.toString();
    }

    private static List<Object> vectorSearchParameters(float[] embedding, String domain, int limit) {
        List<Object> parameters = new ArrayList<>();
        String vectorLiteral = vectorLiteral(embedding);
        parameters.add(vectorLiteral);
        if (domain != null) {
            parameters.add(domain);
        }
        parameters.add(vectorLiteral);
        parameters.add(limit);
        return parameters;
    }

    private static String vectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(embedding[index]);
        }
        return builder.append(']').toString();
    }
}
