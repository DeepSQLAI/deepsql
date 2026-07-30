package com.dbaagent.config;

import com.dbaagent.service.PgVectorStoreSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Ensures the local pgvector-backed RAG store exists before PgVectorSearchService starts.
 *
 * <p>The self-hosted product ships in {@code vector.store.type=pgvector} mode.
 * Hibernate does not manage {@code rag_documents}, so we bootstrap and validate
 * that schema here rather than relying on a Flyway/Liquibase runtime.
 */
@Configuration("pgVectorRagStoreConfig")
@Slf4j
@ConditionalOnProperty(name = "vector.store.type", havingValue = "pgvector")
public class PgVectorRagStoreInitializer {

    private static final String RAG_TABLE = "rag_documents";
    private static final String RAG_SCHEMA = "public";

    @Value("${vector.store.embedding-dimensions:3072}")
    private int embeddingDimensions;

    @Bean("pgVectorRagStoreInitializer")
    public Object pgVectorRagStoreInitializer(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        enablePgVectorExtension(jdbc);

        boolean nativeVectorEnabled = isPgVectorInstalled(jdbc);
        boolean halfvecSupported = nativeVectorEnabled && isHalfvecSupported(jdbc);
        String embeddingType = findEmbeddingColumnType(jdbc);

        if (embeddingType == null) {
            jdbc.execute(nativeVectorEnabled ? createRagDocumentsVectorSql() : createRagDocumentsTextSql());
            log.info("Created {}.{} with embedding type {}",
                    RAG_SCHEMA, RAG_TABLE,
                    nativeVectorEnabled ? PgVectorStoreSupport.vectorColumnType(embeddingDimensions) : "text");
        } else if (nativeVectorEnabled && "text".equalsIgnoreCase(embeddingType)) {
            jdbc.execute("""
                    ALTER TABLE public.rag_documents
                    ALTER COLUMN embedding TYPE %s
                    USING CASE
                        WHEN embedding IS NULL OR btrim(embedding) = '' THEN NULL
                        ELSE embedding::vector
                    END
                    """.formatted(PgVectorStoreSupport.vectorColumnType(embeddingDimensions)));
            log.info("Upgraded {}.{}.embedding from TEXT to {}",
                    RAG_SCHEMA, RAG_TABLE, PgVectorStoreSupport.vectorColumnType(embeddingDimensions));
        }

        ensureDeltaTrackingColumns(jdbc);
        ensureSharedIndexes(jdbc);
        if (nativeVectorEnabled) {
            String annIndexSql = PgVectorStoreSupport.createAnnIndexSql(embeddingDimensions, halfvecSupported);
            if (!annIndexSql.isBlank()) {
                jdbc.execute(annIndexSql);
                log.info("Ensured local pgvector ANN index using {}",
                        PgVectorStoreSupport.describeSearchMode(embeddingDimensions, halfvecSupported));
            } else {
                log.warn("Skipping ANN index creation for {}-dim embeddings because this pgvector build does not support halfvec",
                        embeddingDimensions);
            }
        }

        return new Object();
    }

    @Bean("pgVectorRagStoreValidator")
    @DependsOn("pgVectorRagStoreInitializer")
    public Object pgVectorRagStoreValidator(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String ragTable = jdbc.queryForObject(
                "SELECT COALESCE(to_regclass('public.rag_documents')::text, '')",
                String.class
        );
        if (ragTable == null || ragTable.isBlank()) {
            throw new IllegalStateException("Local pgvector RAG store bootstrap failed: rag_documents table is missing");
        }

        String embeddingType = findEmbeddingColumnType(jdbc);
        if (embeddingType == null || embeddingType.isBlank()) {
            throw new IllegalStateException("Local pgvector RAG store bootstrap failed: rag_documents.embedding is missing");
        }

        String ftsIndex = jdbc.queryForObject(
                "SELECT COALESCE(to_regclass('public.idx_rag_docs_content_fts')::text, '')",
                String.class
        );
        if (ftsIndex == null || ftsIndex.isBlank()) {
            throw new IllegalStateException("Local pgvector RAG store bootstrap failed: idx_rag_docs_content_fts is missing");
        }

        boolean nativeVectorEnabled = isPgVectorInstalled(jdbc);
        boolean halfvecSupported = nativeVectorEnabled && isHalfvecSupported(jdbc);
        if (nativeVectorEnabled) {
            String expectedEmbeddingType = PgVectorStoreSupport.vectorColumnType(embeddingDimensions);
            if (!expectedEmbeddingType.equalsIgnoreCase(embeddingType)) {
                throw new IllegalStateException(
                        "Local pgvector RAG store bootstrap failed: rag_documents.embedding is '"
                                + embeddingType + "' instead of '" + expectedEmbeddingType + "'");
            }

            if (PgVectorStoreSupport.requiresAnnIndex(embeddingDimensions, halfvecSupported)) {
                String annIndex = jdbc.queryForObject(
                        "SELECT COALESCE(to_regclass('public.idx_rag_docs_embedding')::text, '')",
                        String.class
                );
                if (annIndex == null || annIndex.isBlank()) {
                    throw new IllegalStateException("Local pgvector RAG store bootstrap failed: idx_rag_docs_embedding is missing");
                }
            }
        }

        log.info("Validated local RAG store: table={}, embeddingType={}, nativeVectorEnabled={}, searchMode={}",
                ragTable,
                embeddingType,
                nativeVectorEnabled,
                nativeVectorEnabled
                        ? PgVectorStoreSupport.describeSearchMode(embeddingDimensions, halfvecSupported)
                        : "keyword-only local fallback");
        return new Object();
    }

    private void enablePgVectorExtension(JdbcTemplate jdbc) {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            log.info("Ensured pgvector extension exists in the vault database");
        } catch (Exception e) {
            log.warn("pgvector extension is not available; using keyword-only local RAG store. Error: {}", e.getMessage());
        }
    }

    private boolean isPgVectorInstalled(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'",
                Integer.class
        );
        return count != null && count > 0;
    }

    private boolean isHalfvecSupported(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_type WHERE typname = 'halfvec'",
                Integer.class
        );
        return count != null && count > 0;
    }

    private String findEmbeddingColumnType(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT pg_catalog.format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname = 'rag_documents'
                  AND a.attname = 'embedding'
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                """,
                rs -> rs.next() ? rs.getString(1) : null
        );
    }

    private void ensureSharedIndexes(JdbcTemplate jdbc) {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rag_docs_connection ON rag_documents (connection_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rag_docs_type ON rag_documents (type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rag_docs_table ON rag_documents (table_name)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rag_docs_connection_type ON rag_documents (connection_id, type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rag_docs_connection_type_seen ON rag_documents (connection_id, type, last_seen_run_id)");
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rag_docs_content_fts
                ON rag_documents USING gin (
                    to_tsvector(
                        'english',
                        COALESCE(content, '') || ' ' ||
                        COALESCE(natural_language, '') || ' ' ||
                        COALESCE(description, '')
                    )
                )
                """);
    }

    private String createRagDocumentsVectorSql() {
        return """
                CREATE TABLE IF NOT EXISTS rag_documents (
                    id                VARCHAR(128) PRIMARY KEY,
                    connection_id     VARCHAR(64) NOT NULL,
                    type              VARCHAR(32) NOT NULL,
                    content           TEXT,
                    natural_language  TEXT,
                    sql_text          TEXT,
                    object_name       VARCHAR(512),
                    table_name        VARCHAR(512),
                    description       TEXT,
                    business_terms    TEXT,
                    tables_used       TEXT,
                    db_type           VARCHAR(32),
                    embedding         %s,
                    content_hash      VARCHAR(64),
                    last_seen_run_id  VARCHAR(64),
                    metadata          TEXT,
                    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                    successful        BOOLEAN,
                    execution_time_ms BIGINT
                )
                """.formatted(PgVectorStoreSupport.vectorColumnType(embeddingDimensions));
    }

    private String createRagDocumentsTextSql() {
        return """
                CREATE TABLE IF NOT EXISTS rag_documents (
                    id                VARCHAR(128) PRIMARY KEY,
                    connection_id     VARCHAR(64) NOT NULL,
                    type              VARCHAR(32) NOT NULL,
                    content           TEXT,
                    natural_language  TEXT,
                    sql_text          TEXT,
                    object_name       VARCHAR(512),
                    table_name        VARCHAR(512),
                    description       TEXT,
                    business_terms    TEXT,
                    tables_used       TEXT,
                    db_type           VARCHAR(32),
                    embedding         TEXT,
                    content_hash      VARCHAR(64),
                    last_seen_run_id  VARCHAR(64),
                    metadata          TEXT,
                    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                    successful        BOOLEAN,
                    execution_time_ms BIGINT
                )
                """;
    }

    private void ensureDeltaTrackingColumns(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE rag_documents ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64)");
        jdbc.execute("ALTER TABLE rag_documents ADD COLUMN IF NOT EXISTS last_seen_run_id VARCHAR(64)");
    }
}
