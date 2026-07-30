package com.dbaagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * Self-hosted runtime schema compatibility bootstrap for drift-prone columns.
 *
 * <p>The self-hosted Docker image evolves its internal schema with Hibernate
 * {@code ddl-auto=update}, not Flyway. Hibernate emits a new NOT NULL column as
 * {@code ALTER TABLE t ADD COLUMN c <type> not null} <em>without</em> a DEFAULT.
 * Postgres rejects that on a table that already has rows, and the image runs
 * with {@code hibernate.hbm2ddl.halt_on_error=false}, so the failure is
 * swallowed at startup — the container comes up "healthy" with a schema that no
 * longer matches the JPA entities. The first read that hydrates such an entity
 * then fails with {@code ERROR: column ... does not exist} and returns HTTP 500
 * (this is exactly what broke {@code index_recommendations} and the
 * {@code /index-advisor/.../health-report} + {@code /index-recommendations/...}
 * endpoints).
 *
 * <p>This bootstrap runs <strong>after</strong> Hibernate's schema update (via
 * {@code @DependsOn("entityManagerFactory")}) and idempotently adds each
 * drift-prone NOT NULL column <em>with</em> the correct DEFAULT, which succeeds
 * on a populated table. Every statement is {@code ADD COLUMN IF NOT EXISTS}
 * guarded by table existence, so it is a safe no-op once the schema is current
 * and across installs that do not have a given table.
 *
 * <p>Mirrors {@link BrainInitSchemaCompatibilityInitializer}, which handles the
 * same Hibernate-vs-Flyway gap for enum CHECK constraints.
 */
@Configuration
@Slf4j
public class SchemaColumnCompatibilityInitializer {

    /**
     * (table, column, column definition) for each NOT NULL column that
     * Hibernate cannot add to a populated table. The definition carries the
     * same DEFAULT as the canonical migration so existing rows backfill
     * cleanly. Nullable columns are intentionally omitted — Hibernate adds
     * those without issue.
     */
    private record ColumnSpec(String table, String column, String definition) {}

    private static final List<ColumnSpec> DRIFT_PRONE_COLUMNS = List.of(
            // index_recommendations — the confirmed casualty (V93/V94/V95)
            new ColumnSpec("index_recommendations", "occurrence_count",  "INTEGER NOT NULL DEFAULT 1"),
            new ColumnSpec("index_recommendations", "kind",              "VARCHAR(20) NOT NULL DEFAULT 'CREATE_INDEX'"),
            new ColumnSpec("index_recommendations", "workload_score_ms", "BIGINT NOT NULL DEFAULT 0"),
            new ColumnSpec("index_recommendations", "write_cost_score",  "BIGINT NOT NULL DEFAULT 0"),
            new ColumnSpec("index_recommendations", "evidence_count",    "INTEGER NOT NULL DEFAULT 0"),
            // Other NOT NULL adds that the same gap could strand on an upgrade
            new ColumnSpec("schema_documentation",  "source",                            "VARCHAR(20) NOT NULL DEFAULT 'USER'"),
            new ColumnSpec("encrypted_credentials", "enable_data_sampling",              "BOOLEAN NOT NULL DEFAULT TRUE"),
            new ColumnSpec("users",                 "account_status",                    "VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'"),
            new ColumnSpec("query_fingerprints",    "normalization_version",             "INTEGER NOT NULL DEFAULT 1"),
            new ColumnSpec("resource_limits",       "slow_query_history_retention_days", "INTEGER NOT NULL DEFAULT 30"),
            new ColumnSpec("ingestion_jobs",        "truncated",                         "BOOLEAN NOT NULL DEFAULT FALSE")
    );

    @Bean("schemaColumnCompatibilityBootstrap")
    @DependsOn("entityManagerFactory")
    public Object schemaColumnCompatibilityBootstrap(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int repaired = 0;

        for (ColumnSpec spec : DRIFT_PRONE_COLUMNS) {
            if (ensureColumn(jdbc, spec)) {
                repaired++;
            }
        }

        if (repaired > 0) {
            log.warn("Schema column compatibility: backfilled {} drift-prone column(s) "
                    + "that Hibernate could not add to a populated table", repaired);
        }
        return new Object();
    }

    /**
     * @return true if the column was missing and has now been added.
     */
    private boolean ensureColumn(JdbcTemplate jdbc, ColumnSpec spec) {
        if (!tableExists(jdbc, spec.table()) || columnExists(jdbc, spec.table(), spec.column())) {
            return false;
        }
        try {
            jdbc.execute("ALTER TABLE " + spec.table()
                    + " ADD COLUMN IF NOT EXISTS " + spec.column() + " " + spec.definition());
            log.info("Schema column compatibility: added {}.{} ({})",
                    spec.table(), spec.column(), spec.definition());
            return true;
        } catch (Exception e) {
            // Never block startup on a single column — log loudly and continue
            // so the rest of the drift is still reconciled.
            log.error("Schema column compatibility: failed to add {}.{}: {}",
                    spec.table(), spec.column(), e.getMessage(), e);
            return false;
        }
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
