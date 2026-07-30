package com.dbaagent.service;

import com.dbaagent.dto.WorkloadAnalysisData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Detects repeated GROUP BY + aggregate shapes across a workload and recommends
 * a pre-aggregation — a materialized view (Postgres) or summary table (MySQL) —
 * that several queries can read from instead of re-scanning + re-aggregating
 * the base tables on every call.
 *
 * <p>This is the one recommendation type no other engine produces. It's
 * deliberately conservative for v1:
 * <ul>
 *   <li>Only simple {@code PlainSelect} with a non-empty GROUP BY and ≥1
 *       aggregate function is a candidate. UNIONs, set-ops, and queries with
 *       no grouping are skipped.</li>
 *   <li>Queries are grouped by an exact shape key = sorted(tables) +
 *       sorted(dimension expressions). Same shape ⇒ one shared pre-agg whose
 *       measures are the union across the contributing queries.</li>
 *   <li>The generated rollup is UNFILTERED (no per-query WHERE) — the classic
 *       pattern is "materialize the full grouped rollup, let queries filter the
 *       MV on its dimensions". The rationale flags that this assumes the
 *       queries' filters are on the grouped dimensions; a human reviews before
 *       applying. (Filter-subsumption analysis is a future refinement.)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreAggregationAnalyzer {

    private static final Set<String> AGG_FUNCTIONS = Set.of(
        "SUM", "COUNT", "MIN", "MAX", "AVG", "STRING_AGG", "GROUP_CONCAT", "ARRAY_AGG");

    private final ConnectionService connectionService;

    @Value("${workload-analysis.pre-agg.min-shared-queries:2}")
    private int minSharedQueries;

    /** A query the analyzer should consider, with its workload weight. */
    public record QueryInput(String fingerprint, String sql, long workloadScoreMs) {}

    /**
     * Analyze the candidate queries and return pre-aggregation recommendations.
     * Never throws — a query that fails to parse is skipped.
     */
    public List<WorkloadAnalysisData.PreAggRec> analyze(String connectionId, List<QueryInput> queries) {
        if (queries == null || queries.isEmpty()) return List.of();

        boolean isMysql = resolveIsMysql(connectionId);

        // shapeKey -> accumulated shape
        Map<String, Shape> shapes = new LinkedHashMap<>();
        for (QueryInput q : queries) {
            Shape parsed = parseShape(q.sql());
            if (parsed == null) continue;  // not an aggregate-with-grouping query
            Shape agg = shapes.computeIfAbsent(parsed.key(), k -> new Shape(
                parsed.key(), parsed.tables, new LinkedHashSet<>(parsed.dimensions),
                new LinkedHashSet<>(), 0, 0L));
            agg.measures.addAll(parsed.measures);
            agg.queryCount++;
            agg.workloadScoreMs += Math.max(0, q.workloadScoreMs());
        }

        List<WorkloadAnalysisData.PreAggRec> out = new ArrayList<>();
        for (Shape s : shapes.values()) {
            // Emit when several queries share the shape, OR a single query whose
            // aggregate is expensive enough to be worth materializing on its own.
            boolean shared = s.queryCount >= minSharedQueries;
            boolean expensiveSingleton = s.queryCount == 1 && s.workloadScoreMs >= 60_000L; // ≥60s/period
            if (!shared && !expensiveSingleton) continue;
            if (s.dimensions.isEmpty() || s.measures.isEmpty()) continue;

            String name = suggestName(s);
            String ddl = isMysql ? mysqlDdl(name, s) : postgresDdl(name, s);
            String refresh = isMysql
                ? "MySQL has no materialized views — refresh via a scheduled EVENT or app-level job "
                  + "(TRUNCATE + INSERT … SELECT, or maintain incrementally on write). Cadence per freshness need."
                : "REFRESH MATERIALIZED VIEW CONCURRENTLY " + name + "; — schedule nightly (or per freshness need). "
                  + "CONCURRENTLY requires the unique index in the DDL.";

            out.add(WorkloadAnalysisData.PreAggRec.builder()
                .suggestedName(name)
                .sourceTables(new ArrayList<>(s.tables))
                .dimensions(new ArrayList<>(s.dimensions))
                .measures(new ArrayList<>(s.measures))
                .materializationDdl(ddl)
                .refreshStrategy(refresh)
                .sharedByQueries(s.queryCount)
                .workloadScoreMs(s.workloadScoreMs)
                .rationale(buildRationale(s, shared))
                .build());
        }
        // Highest workload payoff first.
        out.sort((a, b) -> Long.compare(b.getWorkloadScoreMs(), a.getWorkloadScoreMs()));
        return out;
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    /** Accumulator for one aggregate shape across queries sharing it. */
    private static final class Shape {
        final String key;
        final Set<String> tables;
        final Set<String> dimensions;
        final Set<String> measures;
        int queryCount;
        long workloadScoreMs;
        Shape(String key, Set<String> tables, Set<String> dimensions, Set<String> measures,
              int queryCount, long workloadScoreMs) {
            this.key = key; this.tables = tables; this.dimensions = dimensions;
            this.measures = measures; this.queryCount = queryCount; this.workloadScoreMs = workloadScoreMs;
        }
        String key() { return key; }
    }

    /**
     * Parse one query into its aggregate shape, or null if it isn't a simple
     * grouped-aggregate SELECT.
     */
    private Shape parseShape(String sql) {
        if (sql == null || sql.isBlank()) return null;
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            // JSQLParser 5.x: PlainSelect IS-A Select (no getSelectBody()).
            if (!(stmt instanceof PlainSelect ps)) return null;

            // Must have a GROUP BY.
            if (ps.getGroupBy() == null
                || ps.getGroupBy().getGroupByExpressionList() == null) {
                return null;
            }
            List<String> dims = new ArrayList<>();
            for (Object e : ps.getGroupBy().getGroupByExpressionList().getExpressions()) {
                dims.add(normalizeExpr(e.toString()));
            }
            if (dims.isEmpty()) return null;

            // Must have ≥1 aggregate function in the SELECT list.
            Set<String> measures = new LinkedHashSet<>();
            if (ps.getSelectItems() != null) {
                for (SelectItem<?> si : ps.getSelectItems()) {
                    collectAggregates(si.getExpression(), measures);
                }
            }
            if (measures.isEmpty()) return null;

            // Tables (FROM + JOINs). Skip if any source is a subquery — too
            // complex to safely materialize in v1.
            Set<String> tables = new LinkedHashSet<>();
            if (!collectTables(ps.getFromItem(), ps.getJoins(), tables) || tables.isEmpty()) {
                return null;
            }

            // Shape key: sorted tables + sorted dimensions (measures don't affect identity).
            String key = new TreeSet<>(tables) + "||" + new TreeSet<>(dims);
            return new Shape(key, tables, new LinkedHashSet<>(dims), measures, 1, 0L);
        } catch (Exception e) {
            log.debug("Pre-agg: skipping unparseable query: {}", e.getMessage());
            return null;
        }
    }

    /** Recursively collect aggregate-function expressions as rendered SQL strings. */
    private void collectAggregates(net.sf.jsqlparser.expression.Expression expr, Set<String> measures) {
        if (expr == null) return;
        if (expr instanceof Function fn) {
            String fname = fn.getName() == null ? "" : fn.getName().toUpperCase(Locale.ROOT);
            if (fn.isAllColumns() && "COUNT".equals(fname)) {
                measures.add("count(*)");
                return;
            }
            if (AGG_FUNCTIONS.contains(fname)) {
                measures.add(normalizeExpr(fn.toString()));
                return;
            }
        }
        // Aggregates can be nested in arithmetic (e.g. SUM(a)/COUNT(*)) — descend.
        if (expr instanceof net.sf.jsqlparser.expression.BinaryExpression be) {
            collectAggregates(be.getLeftExpression(), measures);
            collectAggregates(be.getRightExpression(), measures);
        } else if (expr instanceof net.sf.jsqlparser.expression.Parenthesis p) {
            collectAggregates(p.getExpression(), measures);
        }
    }

    /** Returns false if a non-Table source (subquery) is encountered. */
    private boolean collectTables(FromItem from, List<Join> joins, Set<String> tables) {
        if (from instanceof Table t) {
            tables.add(stripQualifiers(t.getName()));
        } else {
            return false; // subquery / VALUES / function — bail for v1
        }
        if (joins != null) {
            for (Join j : joins) {
                if (j.getRightItem() instanceof Table jt) {
                    tables.add(stripQualifiers(jt.getName()));
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    // ── DDL generation ───────────────────────────────────────────────────────

    private String postgresDdl(String name, Shape s) {
        String dims = String.join(", ", s.dimensions);
        String selectList = dims + ",\n       " + String.join(",\n       ", s.measures);
        String from = renderFrom(s);
        return "CREATE MATERIALIZED VIEW " + name + " AS\n"
            + "SELECT " + selectList + "\n"
            + "FROM " + from + "\n"
            + "GROUP BY " + dims + ";\n"
            + "-- unique index enables REFRESH ... CONCURRENTLY\n"
            + "CREATE UNIQUE INDEX " + name + "_uk ON " + name + " (" + dims + ");";
    }

    private String mysqlDdl(String name, Shape s) {
        String dims = String.join(", ", s.dimensions);
        String selectList = dims + ",\n       " + String.join(",\n       ", s.measures);
        String from = renderFrom(s);
        return "-- MySQL has no materialized views; this is a refreshable summary table.\n"
            + "CREATE TABLE " + name + " AS\n"
            + "SELECT " + selectList + "\n"
            + "FROM " + from + "\n"
            + "GROUP BY " + dims + ";\n"
            + "CREATE UNIQUE INDEX " + name + "_uk ON " + name + " (" + dims + ");";
    }

    /**
     * Render the FROM clause. Single table is trivial. For multi-table shapes we
     * cannot reconstruct the original JOIN predicates from the grouped shape, so
     * we emit a CROSS-join placeholder the human MUST complete — flagged loudly.
     */
    private String renderFrom(Shape s) {
        List<String> tableList = new ArrayList<>(s.tables);
        if (tableList.size() == 1) {
            return tableList.get(0);
        }
        return String.join("\n     /* TODO: restore JOIN conditions */ JOIN ", tableList);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean resolveIsMysql(String connectionId) {
        try {
            String dbType = connectionService.getDbType(connectionId);
            return dbType != null && dbType.toLowerCase(Locale.ROOT).contains("mysql");
        } catch (Exception e) {
            log.debug("Pre-agg: dbType resolution failed for {}, defaulting to Postgres DDL: {}",
                connectionId, e.getMessage());
            return false;
        }
    }

    private String suggestName(Shape s) {
        String table = s.tables.iterator().next();
        String dimPart = s.dimensions.stream()
            .map(d -> d.replaceAll("[^a-zA-Z0-9]+", "_"))
            .reduce((a, b) -> a + "_" + b).orElse("rollup");
        String raw = "mv_" + table + "_by_" + dimPart;
        raw = raw.replaceAll("_+", "_").replaceAll("_$", "");
        return raw.length() > 60 ? raw.substring(0, 60) : raw;
    }

    private String buildRationale(Shape s, boolean shared) {
        String lead = shared
            ? s.queryCount + " queries share this GROUP BY " + s.dimensions + " on " + s.tables
              + " — materialize once, read many."
            : "A single high-cost aggregate (~" + (s.workloadScoreMs / 1000) + "s/period) over "
              + s.tables + " grouped by " + s.dimensions + " — worth materializing.";
        return lead + " Assumes query filters are on the grouped dimensions; the rollup is unfiltered. "
            + "Review the generated DDL (and JOIN conditions for multi-table shapes) before applying.";
    }

    private static String normalizeExpr(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String stripQualifiers(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        if (s.length() >= 2) {
            char f = s.charAt(0), l = s.charAt(s.length() - 1);
            if ((f == '`' && l == '`') || (f == '"' && l == '"') || (f == '[' && l == ']')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
