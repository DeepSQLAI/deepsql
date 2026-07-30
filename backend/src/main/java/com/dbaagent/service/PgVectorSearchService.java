package com.dbaagent.service;

import com.dbaagent.model.QualifiedTableName;
import com.dbaagent.model.TrainingDataSearchDocument;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Self-hosted vector search backed by PostgreSQL + pgvector.
 *
 * <p>Activated when {@code vector.store.type=pgvector}. Stores RAG embeddings
 * in the {@code rag_documents} table and performs similarity search using
 * pgvector cosine distance. For high-dimensional embeddings such as
 * {@code text-embedding-3-large}, ANN search is accelerated via a halfvec-based
 * HNSW index when the local pgvector build supports it.
 *
 * <p>Falls back gracefully to a local keyword-only store when pgvector is not
 * installed. In that mode documents are still persisted to {@code rag_documents}
 * and searched via PostgreSQL full-text search.
 *
 * <p>This bean is {@code @Primary}, so it takes precedence over
 * {@link AzureSearchService} for all {@link VectorSearchService} injection points.
 */
@Service
@Primary
@DependsOn("pgVectorRagStoreValidator")
@ConditionalOnProperty(name = "vector.store.type", havingValue = "pgvector")
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PgVectorSearchService implements VectorSearchService {

    /** Vault DataSource JdbcTemplate (injected by Spring from the primary DataSource). */
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseProviderRegistry databaseProviderRegistry;
    private final ObjectMapper objectMapper;

    @Value("${vector.store.embedding-dimensions:3072}")
    private int embeddingDimensions;

    private volatile boolean pgvectorAvailable = false;
    private volatile boolean ragStoreReady = false;
    private volatile boolean halfvecSupported = false;

    // ── Startup ───────────────────────────────────────────────────────────────

    @PostConstruct
    public void initialize() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'",
                    Integer.class);
            pgvectorAvailable = count != null && count > 0;

            String ragTable = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(to_regclass('public.rag_documents')::text, '')",
                    String.class);
            ragStoreReady = ragTable != null && !ragTable.isBlank();

            Integer halfvecCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_type WHERE typname = 'halfvec'",
                    Integer.class);
            halfvecSupported = halfvecCount != null && halfvecCount > 0;

            if (!ragStoreReady) {
                log.error("PgVectorSearchService: rag_documents table is missing. Local RAG store is unavailable.");
            } else if (pgvectorAvailable) {
                log.info("PgVectorSearchService ready — native {} storage with {}",
                        PgVectorStoreSupport.vectorColumnType(embeddingDimensions),
                        PgVectorStoreSupport.describeSearchMode(embeddingDimensions, halfvecSupported));
            } else {
                log.warn("PgVectorSearchService: pgvector extension not installed. "
                        + "Running in keyword-only local RAG mode.");
            }
        } catch (Exception e) {
            log.error("PgVectorSearchService: failed to check pgvector availability", e);
            pgvectorAvailable = false;
            ragStoreReady = false;
            halfvecSupported = false;
        }
    }

    // ── VectorSearchService: state ────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return ragStoreReady;
    }

    /**
     * pgvector always supports table-based filtering (unlike Azure which requires V2_ACTIVE).
     */
    @Override
    public boolean isStage2Enabled() {
        return true;
    }

    @Override
    public String backendName() {
        return "Local pgvector";
    }

    // ── VectorSearchService: write operations ─────────────────────────────────

    @Override
    public void indexDocument(TrainingDataSearchDocument doc) {
        if (doc == null) return;
        indexDocuments(List.of(doc));
    }

    @Override
    public void indexDocuments(List<TrainingDataSearchDocument> documents) {
        if (!ragStoreReady || documents == null || documents.isEmpty()) return;

        String upsertSql = pgvectorAvailable
                ? """
                  INSERT INTO rag_documents (
                      id, connection_id, type, content, natural_language, sql_text,
                      object_name, table_name, description, business_terms,
                      tables_used, db_type, embedding, content_hash, last_seen_run_id, metadata,
                      created_at, successful, execution_time_ms
                  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?::timestamptz, ?, ?)
                  ON CONFLICT (id) DO UPDATE SET
                      connection_id     = EXCLUDED.connection_id,
                      type              = EXCLUDED.type,
                      content           = EXCLUDED.content,
                      natural_language  = EXCLUDED.natural_language,
                      sql_text          = EXCLUDED.sql_text,
                      object_name       = EXCLUDED.object_name,
                      table_name        = EXCLUDED.table_name,
                      description       = EXCLUDED.description,
                      business_terms    = EXCLUDED.business_terms,
                      tables_used       = EXCLUDED.tables_used,
                      db_type           = EXCLUDED.db_type,
                      embedding         = CASE
                                              WHEN EXCLUDED.embedding IS NULL
                                                   AND EXCLUDED.content_hash IS NOT NULL
                                                   AND EXCLUDED.content_hash = rag_documents.content_hash
                                              THEN rag_documents.embedding
                                              ELSE EXCLUDED.embedding
                                          END,
                      content_hash      = EXCLUDED.content_hash,
                      last_seen_run_id  = EXCLUDED.last_seen_run_id,
                      metadata          = EXCLUDED.metadata,
                      created_at        = EXCLUDED.created_at,
                      successful        = EXCLUDED.successful,
                      execution_time_ms = EXCLUDED.execution_time_ms
                  """
                : """
                  INSERT INTO rag_documents (
                      id, connection_id, type, content, natural_language, sql_text,
                      object_name, table_name, description, business_terms,
                      tables_used, db_type, embedding, content_hash, last_seen_run_id, metadata,
                      created_at, successful, execution_time_ms
                  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?, ?)
                  ON CONFLICT (id) DO UPDATE SET
                      connection_id     = EXCLUDED.connection_id,
                      type              = EXCLUDED.type,
                      content           = EXCLUDED.content,
                      natural_language  = EXCLUDED.natural_language,
                      sql_text          = EXCLUDED.sql_text,
                      object_name       = EXCLUDED.object_name,
                      table_name        = EXCLUDED.table_name,
                      description       = EXCLUDED.description,
                      business_terms    = EXCLUDED.business_terms,
                      tables_used       = EXCLUDED.tables_used,
                      db_type           = EXCLUDED.db_type,
                      embedding         = CASE
                                              WHEN EXCLUDED.embedding IS NULL
                                                   AND EXCLUDED.content_hash IS NOT NULL
                                                   AND EXCLUDED.content_hash = rag_documents.content_hash
                                              THEN rag_documents.embedding
                                              ELSE EXCLUDED.embedding
                                          END,
                      content_hash      = EXCLUDED.content_hash,
                      last_seen_run_id  = EXCLUDED.last_seen_run_id,
                      metadata          = EXCLUDED.metadata,
                      created_at        = EXCLUDED.created_at,
                      successful        = EXCLUDED.successful,
                      execution_time_ms = EXCLUDED.execution_time_ms
                  """;

        List<Object[]> batchArgs = documents.stream()
                .map(doc -> {
                    RagInternalMetadata internalMetadata = extractInternalMetadata(doc.getMetadata());
                    return new Object[]{
                        doc.getId(),
                        doc.getConnectionId(),
                        doc.getType(),
                        doc.getContent(),
                        doc.getNaturalLanguage(),
                        doc.getSql(),
                        doc.getObjectName(),
                        doc.getTableName(),
                        doc.getDescription(),
                        doc.getBusinessTerms(),
                        doc.getTablesUsed(),
                        doc.getDbType(),
                        normalizeVectorForStorage(doc.getId(), doc.getContentVector()),
                        internalMetadata.contentHash(),
                        internalMetadata.lastSeenRunId(),
                        doc.getMetadata(),
                        normalizeCreatedAt(doc.getCreatedAt()),
                        doc.getSuccessful(),
                        doc.getExecutionTimeMs()
                    };
                })
                .collect(Collectors.toList());

        try {
            jdbcTemplate.batchUpdate(upsertSql, batchArgs);
            log.debug("PgVector: upserted {} documents", documents.size());
        } catch (Exception e) {
            log.error("PgVector: failed to index {} documents", documents.size(), e);
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        if (!ragStoreReady || documentId == null) return;
        try {
            jdbcTemplate.update("DELETE FROM rag_documents WHERE id = ?", documentId);
            log.debug("PgVector: deleted document {}", documentId);
        } catch (Exception e) {
            log.error("PgVector: failed to delete document {}", documentId, e);
        }
    }

    @Override
    public void deleteConnectionDocuments(String connectionId) {
        if (!ragStoreReady || connectionId == null) return;
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM rag_documents WHERE connection_id = ?", connectionId);
            log.info("PgVector: deleted {} documents for connection {}", deleted, connectionId);
        } catch (Exception e) {
            log.error("PgVector: failed to delete documents for connection {}", connectionId, e);
        }
    }

    // ── VectorSearchService: search operations ────────────────────────────────

    @Override
    public List<TrainingDataSearchDocument> hybridSearch(
            String connectionId, String query, List<Float> queryVector, int topK) {

        if (connectionId == null) return List.of();
        if (!ragStoreReady) return List.of();

        try {
            // Vector path: use cosine similarity via pgvector
            if (pgvectorAvailable && queryVector != null && !queryVector.isEmpty()) {
                String vectorStr = normalizeQueryVector(queryVector, connectionId);
                if (vectorStr == null) {
                    return query != null && !query.isBlank() ? keywordSearch(connectionId, query, topK) : List.of();
                }
                String distanceExpr = PgVectorStoreSupport.distanceExpression(
                        embeddingDimensions, halfvecSupported, "?");
                String sql = """
                        SELECT *, 1 - (%s) AS search_score
                        FROM rag_documents
                        WHERE connection_id = ?
                          AND embedding IS NOT NULL
                        ORDER BY %s
                        LIMIT ?
                        """.formatted(distanceExpr, distanceExpr);
                return jdbcTemplate.query(sql,
                        DOC_ROW_MAPPER,
                        vectorStr, connectionId, vectorStr, topK);
            }

            // Keyword fallback: full-text search via tsvector
            if (query != null && !query.isBlank()) {
                return keywordSearch(connectionId, query, topK);
            }
        } catch (Exception e) {
            log.error("PgVector: hybridSearch failed for connection {}", connectionId, e);
        }

        return List.of();
    }

    @Override
    public List<TrainingDataSearchDocument> vectorSearch(
            String connectionId, List<Float> queryVector, int topK, String typeFilter) {

        if (connectionId == null || queryVector == null || queryVector.isEmpty()) {
            return List.of();
        }
        if (!ragStoreReady) {
            return List.of();
        }

        try {
            if (pgvectorAvailable) {
                String vectorStr = normalizeQueryVector(queryVector, connectionId);
                if (vectorStr == null) {
                    return List.of();
                }
                boolean hasTypeFilter = typeFilter != null && !typeFilter.isBlank();
                String distanceExpr = PgVectorStoreSupport.distanceExpression(
                        embeddingDimensions, halfvecSupported, "?");

                String sql = hasTypeFilter
                        ? """
                          SELECT *, 1 - (%s) AS search_score
                          FROM rag_documents
                          WHERE connection_id = ? AND type = ? AND embedding IS NOT NULL
                          ORDER BY %s
                          LIMIT ?
                          """.formatted(distanceExpr, distanceExpr)
                        : """
                          SELECT *, 1 - (%s) AS search_score
                          FROM rag_documents
                          WHERE connection_id = ? AND embedding IS NOT NULL
                          ORDER BY %s
                          LIMIT ?
                          """.formatted(distanceExpr, distanceExpr);

                if (hasTypeFilter) {
                    return jdbcTemplate.query(sql, DOC_ROW_MAPPER,
                            vectorStr, connectionId, typeFilter, vectorStr, topK);
                } else {
                    return jdbcTemplate.query(sql, DOC_ROW_MAPPER,
                            vectorStr, connectionId, vectorStr, topK);
                }
            }
        } catch (Exception e) {
            log.error("PgVector: vectorSearch failed for connection {}", connectionId, e);
        }

        return List.of();
    }

    @Override
    public List<TrainingDataSearchDocument> filterByTables(
            String connectionId, Set<QualifiedTableName> resolvedTables, int maxResults) {

        if (connectionId == null || resolvedTables == null || resolvedTables.isEmpty()) {
            return List.of();
        }
        if (!ragStoreReady) {
            return List.of();
        }

        try {
            // Build table name list (bare + schema-qualified forms)
            Set<String> tableNames = new HashSet<>();
            Map<String, Long> bareNameCounts = resolvedTables.stream()
                    .collect(Collectors.groupingBy(
                            qt -> qt.bare().toLowerCase(Locale.ROOT), Collectors.counting()));

            for (QualifiedTableName qt : resolvedTables) {
                tableNames.add(qt.storedForm());
                if (qt.schemaQualified() != null
                        && bareNameCounts.getOrDefault(qt.bare().toLowerCase(Locale.ROOT), 0L) == 1) {
                    tableNames.add(qt.bare());
                }
            }

            String placeholders = tableNames.stream().map(t -> "?").collect(Collectors.joining(", "));
            String sql = String.format("""
                    SELECT *, NULL::double precision AS search_score
                    FROM rag_documents
                    WHERE connection_id = ?
                      AND table_name IN (%s)
                      AND type <> 'QUERY_EXAMPLE'
                    LIMIT ?
                    """, placeholders);

            List<Object> params = new ArrayList<>();
            params.add(connectionId);
            params.addAll(tableNames);
            params.add(maxResults);

            return jdbcTemplate.query(sql, DOC_ROW_MAPPER, params.toArray());

        } catch (Exception e) {
            log.error("PgVector: filterByTables failed for connection {}", connectionId, e);
            return List.of();
        }
    }

    // ── VectorSearchService: utilities ────────────────────────────────────────

    @Override
    public String resolveTableName(String objectName, String parentObject,
                                   String objectType, String dbType) {
        String raw = "COLUMN".equals(objectType) ? parentObject : objectName;
        if (raw == null) return null;

        int dot = raw.lastIndexOf('.');
        if (dot < 0) return raw;

        String schema = raw.substring(0, dot);
        String table  = raw.substring(dot + 1);

        String defaultSchema;
        try {
            defaultSchema = databaseProviderRegistry.getDialect(dbType)
                    .introspection().getDefaultSchema();
        } catch (Exception e) {
            log.warn("PgVector: could not resolve default schema for dbType={}", dbType);
            return table;
        }

        if (defaultSchema == null) return table;
        return schema.equalsIgnoreCase(defaultSchema) ? table : raw;
    }

    @Override
    public Map<String, Long> getConnectionStats(String connectionId) {
        if (!ragStoreReady || connectionId == null) return Collections.emptyMap();

        try {
            Map<String, Long> stats = new LinkedHashMap<>();
            List<String> types = List.of("SCHEMA_DDL", "QUERY_EXAMPLE", "DOCUMENTATION",
                    "COLUMN_VALUES", "RELATIONSHIP", "BUSINESS_TERM", "COMPANY_KNOWLEDGE");

            for (String type : types) {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM rag_documents WHERE connection_id = ? AND type = ?",
                        Long.class, connectionId, type);
                stats.put(type.toLowerCase(), count != null ? count : 0L);
            }
            return stats;
        } catch (Exception e) {
            log.error("PgVector: getConnectionStats failed for connection {}", connectionId, e);
            return Collections.emptyMap();
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private List<TrainingDataSearchDocument> keywordSearch(
            String connectionId, String query, int topK) {
        String sql = """
                SELECT *,
                       ts_rank(
                           to_tsvector('english',
                               COALESCE(content, '') || ' ' ||
                               COALESCE(natural_language, '') || ' ' ||
                               COALESCE(description, '')
                           ),
                           plainto_tsquery('english', ?)
                       ) AS search_score
                FROM rag_documents
                WHERE connection_id = ?
                  AND to_tsvector('english',
                          COALESCE(content, '') || ' ' ||
                          COALESCE(natural_language, '') || ' ' ||
                          COALESCE(description, '')
                      ) @@ plainto_tsquery('english', ?)
                ORDER BY search_score DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, DOC_ROW_MAPPER, query, connectionId, query, topK);
    }

    private String normalizeVectorForStorage(String documentId, List<Float> vector) {
        String literal = PgVectorStoreSupport.toVectorLiteral(vector, embeddingDimensions);
        if (vector != null && !vector.isEmpty() && literal == null) {
            log.warn("PgVector: skipping embedding for document {} because vector length {} does not match configured {}",
                    documentId, vector.size(), embeddingDimensions);
        }
        return literal;
    }

    private String normalizeQueryVector(List<Float> queryVector, String connectionId) {
        String literal = PgVectorStoreSupport.toVectorLiteral(queryVector, embeddingDimensions);
        if (queryVector != null && !queryVector.isEmpty() && literal == null) {
            log.warn("PgVector: query vector length {} does not match configured {} for connection {}. Falling back.",
                    queryVector.size(), embeddingDimensions, connectionId);
        }
        return literal;
    }

    private String normalizeCreatedAt(String createdAt) {
        return createdAt != null && !createdAt.isBlank()
                ? createdAt
                : OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    private RagInternalMetadata extractInternalMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return new RagInternalMetadata(null, null);
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                metadata,
                new TypeReference<Map<String, Object>>() {
                }
            );
            String contentHash = parsed.get("_contentHash") != null
                ? String.valueOf(parsed.get("_contentHash"))
                : null;
            String lastSeenRunId = parsed.get("_lastSeenRunId") != null
                ? String.valueOf(parsed.get("_lastSeenRunId"))
                : null;
            return new RagInternalMetadata(contentHash, lastSeenRunId);
        } catch (Exception e) {
            log.debug("PgVector: could not parse internal metadata for rag document: {}", e.getMessage());
            return new RagInternalMetadata(null, null);
        }
    }

    /** Maps a {@code rag_documents} row to a {@link TrainingDataSearchDocument}. */
    private static final RowMapper<TrainingDataSearchDocument> DOC_ROW_MAPPER =
            (rs, rowNum) -> mapRow(rs);

    private record RagInternalMetadata(String contentHash, String lastSeenRunId) {
    }

    private static TrainingDataSearchDocument mapRow(ResultSet rs) throws SQLException {
        TrainingDataSearchDocument doc = new TrainingDataSearchDocument();
        doc.setId(rs.getString("id"));
        doc.setConnectionId(rs.getString("connection_id"));
        doc.setType(rs.getString("type"));
        doc.setContent(rs.getString("content"));
        doc.setNaturalLanguage(rs.getString("natural_language"));
        doc.setSql(rs.getString("sql_text"));
        doc.setObjectName(rs.getString("object_name"));
        doc.setTableName(rs.getString("table_name"));
        doc.setDescription(rs.getString("description"));
        doc.setBusinessTerms(rs.getString("business_terms"));
        doc.setTablesUsed(rs.getString("tables_used"));
        doc.setDbType(rs.getString("db_type"));
        doc.setMetadata(rs.getString("metadata"));
        doc.setSuccessful(rs.getObject("successful") != null ? rs.getBoolean("successful") : null);
        doc.setExecutionTimeMs(rs.getObject("execution_time_ms") != null ? rs.getLong("execution_time_ms") : null);

        // search_score is a computed column present in search queries
        try {
            double score = rs.getDouble("search_score");
            if (!rs.wasNull()) doc.setSearchScore(score);
        } catch (SQLException ignored) {
            // search_score not present in all queries
        }

        return doc;
    }
}
