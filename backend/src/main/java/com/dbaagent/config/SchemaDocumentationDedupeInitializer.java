package com.dbaagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Applies {@code V116__dedupe_schema_documentation.sql} at startup: collapses
 * duplicate {@code schema_documentation} rows and adds the unique index on the
 * logical key.
 *
 * <p>This repo has no Flyway runtime — {@code db/migration} is a hand-maintained
 * changelog and Hibernate {@code ddl-auto=update} never adds an index the entity
 * does not declare. Without this, self-host installs carrying duplicates from a
 * double-submitted bulk approve stay wedged: every SCHEMA_DOC approve throws
 * {@code Query did not return a unique result}. Mirrors
 * {@link SchemaDocumentationSourceCompatibilityInitializer}.
 *
 * <p>Idempotent and cheap on a clean install: the index exists, so it returns
 * before touching a row.
 */
@Configuration
@Slf4j
public class SchemaDocumentationDedupeInitializer {

    private static final String TABLE = "schema_documentation";
    private static final String INDEX = "ux_schema_doc_target";

    @Bean("schemaDocumentationDedupeBootstrap")
    @DependsOn("entityManagerFactory")
    public Object schemaDocumentationDedupeBootstrap(DataSource dataSource,
                                                     PlatformTransactionManager txManager) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (!tableExists(jdbc, TABLE)) {
            return new Object();
        }
        if (indexExists(jdbc, INDEX)) {
            return new Object();
        }

        // One transaction: a half-applied dedupe (rows deleted, index missing)
        // would silently re-accumulate duplicates until the next boot.
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            int repointed = jdbc.update("""
                UPDATE code_knowledge_suggestion s
                SET applied_doc_id = l.keep_id
                FROM (%s) l
                WHERE s.applied_doc_id = l.id
                """.formatted(LOSERS));

            int embeddings = jdbc.update(
                "DELETE FROM rag_documents WHERE id IN (SELECT id FROM (%s) l)".formatted(LOSERS));

            int removed = jdbc.update(
                "DELETE FROM schema_documentation WHERE id IN (SELECT id FROM (%s) l)".formatted(LOSERS));

            jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS %s
                ON %s (connection_id, object_type, object_name, coalesce(parent_object, ''), source)
                """.formatted(INDEX, TABLE));

            if (removed > 0) {
                log.warn("Deduped {}: removed {} duplicate rows, {} orphaned embeddings, "
                        + "repointed {} applied_doc_id references",
                    TABLE, removed, embeddings, repointed);
            }
            log.info("Ensured unique index {} on {}", INDEX, TABLE);
        });
        return new Object();
    }

    /**
     * Every row but the newest within each logical key. Newest wins because it is
     * the row existing {@code applied_doc_id} references point at; {@code id}
     * breaks ties for rows written in the same clock tick. {@code coalesce} on
     * {@code parent_object} because Postgres treats NULLs as distinct, so TABLE
     * rows would otherwise never group together.
     */
    private static final String LOSERS = """
        SELECT id, keep_id FROM (
            SELECT id,
                   first_value(id) OVER w AS keep_id,
                   row_number()    OVER w AS rn
            FROM schema_documentation
            WINDOW w AS (
                PARTITION BY connection_id, object_type, object_name,
                             coalesce(parent_object, ''), source
                ORDER BY created_at DESC NULLS LAST, id DESC
            )
        ) ranked WHERE rn > 1
        """;

    private boolean tableExists(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean indexExists(JdbcTemplate jdbc, String indexName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }
}
