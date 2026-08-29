package com.dbaagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Adds the stored normalized column and index that make slow-query sample recovery
 * indexable.
 *
 * <p>{@code QueryLineageRepository.findLongestByConnectionIdAndNormalizedQueryTextPrefix}
 * wraps {@code query_text} in three nested {@code regexp_replace}/{@code REPLACE} calls
 * plus {@code LOWER} before comparing it, so no index on {@code query_text} can ever
 * satisfy the predicate — Postgres must materialize a rewritten copy of every row in the
 * connection's slice. {@code EXPLAIN (ANALYZE)} on a real install:
 *
 * <pre>
 *   Seq Scan on query_lineage (actual time=0.244..35.826 rows=422 loops=1)
 *   Execution Time: 36.179 ms          -- at 1,093 rows
 *   Execution Time: 1111.996 ms        -- same query, table scaled to 34,976 rows
 * </pre>
 *
 * <p>{@code SlowQueryAnalyticsService.recoverFullText} runs that once per sample, up to
 * 20 per "view full query" click, so one modal open costs ~0.7 s today and ~22 s at 35k
 * rows. It degrades with <em>age</em> rather than load, which is why it passes every
 * pre-launch test: {@code query_lineage} is not pruned by
 * {@code SlowQueryRetentionService} (that only touches {@code slow_query_run},
 * {@code slow_query_customer_day} and {@code slow_query_sample}), so it only grows.
 *
 * <p>Precomputing the normalization into a STORED generated column pays the regex chain
 * once at write time. Measured on the same scaled table, with the ~120-character prefix
 * the caller actually sends:
 *
 * <pre>
 *   Index Scan using idx_query_lineage_norm_match
 *   Execution Time: 0.428 ms           -- vs 1111.996 ms
 * </pre>
 *
 * <p>The index earns its keep only because the prefix is long and therefore selective. A
 * short prefix such as {@code 'select%'} still plans as a sequential scan (~50 ms at 35k
 * rows) — that is the precomputation alone, and is fine. Do not "simplify" this by
 * dropping the generated column and indexing {@code query_text} directly; the expression,
 * not the column, is what the query compares.
 *
 * <p>There is no Flyway runtime in this repo (see CLAUDE.md), so this initializer is what
 * actually applies {@code V118__add_query_lineage_norm_match.sql}. Both statements are
 * {@code IF NOT EXISTS} and the whole thing is best-effort: a failure here costs
 * performance, never correctness, since the query returns identical rows either way.
 */
@Configuration
@Slf4j
public class QueryLineageMatchIndexInitializer {

    private static final String TABLE = "query_lineage";
    private static final String COLUMN = "normalized_match";
    private static final String INDEX = "idx_query_lineage_norm_match";

    /**
     * Must match {@code SlowQueryAnalyticsService.normalizeForMatching} exactly. If one
     * changes, both do.
     *
     * <p>The {@code btrim} is load-bearing and was missing from the expression this
     * replaces: Java's {@code normalizeForMatching} ends with {@code .trim()}, so a
     * lineage row stored with leading whitespace normalized to {@code " select ..."} on
     * the SQL side and {@code "select ..."} on the Java side. The prefix {@code LIKE}
     * then never matched and recovery silently returned the truncated sample. 4 of 1,174
     * rows on the local install carry such whitespace.
     */
    private static final String NORMALIZE_EXPR =
        "btrim(lower(regexp_replace(regexp_replace("
            + "replace(query_text, '`', ''), "
            + "'\\s*([.,();])\\s*', '\\1', 'g'), "
            + "'\\s+', ' ', 'g')))";

    @Bean("queryLineageMatchIndexBootstrap")
    @DependsOn("entityManagerFactory")
    public Object queryLineageMatchIndexBootstrap(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        if (!tableExists(jdbc, TABLE)) {
            return new Object();
        }

        // A generated column's expression cannot be altered in place, and ADD COLUMN IF
        // NOT EXISTS silently keeps whatever definition is already there. An install that
        // ran an earlier build of this initializer therefore keeps the untrimmed
        // expression forever unless the column is dropped first. Only drop when the
        // definition actually differs, so a normal restart does not rewrite the table.
        if (columnDefinitionDiffers(jdbc)) {
            log.info("Rebuilding {}.{}: stored expression is out of date", TABLE, COLUMN);
            try {
                jdbc.execute("ALTER TABLE " + TABLE + " DROP COLUMN " + COLUMN);
            } catch (RuntimeException e) {
                log.warn("Could not drop stale {}.{}: {}", TABLE, COLUMN, e.getMessage());
            }
        }

        try {
            jdbc.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS " + COLUMN
                + " text GENERATED ALWAYS AS (" + NORMALIZE_EXPR + ") STORED");
        } catch (RuntimeException e) {
            // Generated columns need Postgres 12+. Older servers keep the sequential scan,
            // which is slow but correct, so this must not stop the application.
            log.warn("Could not add {}.{} ({}); sample recovery stays on a sequential scan",
                TABLE, COLUMN, e.getMessage());
            return new Object();
        }

        try {
            // text_pattern_ops so a LIKE 'prefix%' comparison can use the index under any
            // collation; the default opclass only helps in the C collation.
            jdbc.execute("CREATE INDEX IF NOT EXISTS " + INDEX + " ON " + TABLE
                + " (connection_id, " + COLUMN + " text_pattern_ops)");
        } catch (RuntimeException e) {
            log.warn("Could not create {}: {}", INDEX, e.getMessage());
        }

        return new Object();
    }

    /**
     * True when {@code normalized_match} exists but was generated by a different
     * expression than {@link #NORMALIZE_EXPR}. Compared on the normalized form Postgres
     * stores in {@code pg_get_expr}, with whitespace collapsed, since the server rewrites
     * the text it was given (adds casts, reorders parens) and a literal comparison would
     * report a difference on every start and rewrite the table each time.
     */
    private static boolean columnDefinitionDiffers(JdbcTemplate jdbc) {
        try {
            String stored = jdbc.query(
                "SELECT pg_get_expr(d.adbin, d.adrelid) "
                    + "FROM pg_attrdef d "
                    + "JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum "
                    + "WHERE d.adrelid = ?::regclass AND a.attname = ?",
                rs -> rs.next() ? rs.getString(1) : null, TABLE, COLUMN);
            if (stored == null) {
                return false; // column not present yet — nothing stale to drop
            }
            return !squash(stored).contains("btrim");
        } catch (RuntimeException e) {
            log.warn("Could not inspect {}.{} definition: {}", TABLE, COLUMN, e.getMessage());
            return false;
        }
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "").toLowerCase();
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        try {
            Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = current_schema() AND table_name = ?",
                Integer.class, table);
            return found != null && found > 0;
        } catch (RuntimeException e) {
            log.warn("Could not check for table {}: {}", table, e.getMessage());
            return false;
        }
    }
}
