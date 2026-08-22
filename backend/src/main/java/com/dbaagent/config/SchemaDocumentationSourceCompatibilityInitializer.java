package com.dbaagent.config;

import com.dbaagent.model.DocumentationSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Keeps {@code schema_documentation.source} CHECK aligned with
 * {@link DocumentationSource}.
 *
 * <p>Hibernate {@code ddl-auto=update} does not rewrite CHECK constraints when an
 * enum gains a value. Self-host installs that predate V90 therefore reject
 * {@code CODE_DERIVED} rows written by code-scan suggestion approve — the Review
 * queue shows {@code APPROVED 0 OF N} while every SCHEMA_DOC decide is swallowed
 * by bulk-decide. Mirrors {@link BrainInitSchemaCompatibilityInitializer}.
 */
@Configuration
@Slf4j
public class SchemaDocumentationSourceCompatibilityInitializer {

    private static final String TABLE = "schema_documentation";
    private static final String COLUMN = "source";
    private static final String CONSTRAINT = "schema_documentation_source_check";

    @Bean("schemaDocumentationSourceCompatibilityBootstrap")
    @DependsOn("entityManagerFactory")
    public Object schemaDocumentationSourceCompatibilityBootstrap(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (!tableExists(jdbc, TABLE) || !columnExists(jdbc, TABLE, COLUMN)) {
            return new Object();
        }

        String allowed = Arrays.stream(DocumentationSource.values())
            .map(DocumentationSource::name)
            .map(v -> "'" + v + "'")
            .collect(Collectors.joining(", "));

        jdbc.execute("ALTER TABLE " + TABLE + " DROP CONSTRAINT IF EXISTS " + CONSTRAINT);
        jdbc.execute(
            "ALTER TABLE " + TABLE
                + " ADD CONSTRAINT " + CONSTRAINT
                + " CHECK ((" + COLUMN + ")::text = ANY (ARRAY[" + allowed + "]::text[]))"
        );
        log.info("Ensured {} allows DocumentationSource values: {}", CONSTRAINT, allowed);
        return new Object();
    }

    private boolean tableExists(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(JdbcTemplate jdbc, String tableName, String columnName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }
}
