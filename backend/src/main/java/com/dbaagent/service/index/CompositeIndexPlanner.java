package com.dbaagent.service.index;

import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds composite-index candidates from accumulated query evidence using
 * the column-ordering rules every DBA reference recommends (Microsoft DTA
 * white papers, pganalyze docs, the Postgres official docs on B-tree
 * effective use):
 *
 *   1. Equality predicates first, ordered by descending selectivity.
 *   2. One range predicate at the end (only the highest-impact one wins;
 *      a B-tree can only use one range column effectively).
 *   3. ORDER BY columns appended ONLY when the equality prefix fully covers
 *      the WHERE clause, otherwise the index can't help the sort.
 *   4. Cap at 3 columns. Wider composites bloat write cost faster than they
 *      help reads.
 *
 * Inputs are pre-aggregated {@link CandidateGroup}s — one per
 * {@code (table, role-tagged column set)}. The service builds these by
 * accumulating workload-weighted evidence across the lookback window.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CompositeIndexPlanner {

    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;

    /**
     * Workload-score minimum (ms) before a candidate is worth emitting. The
     * default of 1s is intentionally permissive: it surfaces evidence on
     * dev/low-traffic connections where any sustained pattern matters. Prod
     * users can tune up via {@code index-recommendations.composite.min-workload-ms}
     * (pganalyze defaults this at ~60s of cumulative time before it shows a
     * suggestion).
     */
    @Value("${index-recommendations.composite.min-workload-ms:1000}")
    private long minWorkloadMs;

    /** Hard cap on composite columns. */
    @Value("${index-recommendations.composite.max-columns:3}")
    private int maxColumns;

    /**
     * A single workload-aggregated candidate, before column ordering.
     */
    public static class CandidateGroup {
        public final String table;
        public final Set<TableColumnRef> equalityColumns = new LinkedHashSet<>();
        public final Set<TableColumnRef> rangeColumns = new LinkedHashSet<>();
        public final Set<TableColumnRef> orderByColumns = new LinkedHashSet<>();
        public final Set<TableColumnRef> groupByColumns = new LinkedHashSet<>();
        public long workloadScoreMs = 0L;
        public int evidenceCount = 0;

        public CandidateGroup(String table) {
            this.table = table == null ? null : table.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Final composite recommendation — ordered column list + score.
     */
    public record Plan(
        String table,
        List<String> columnsInOrder,
        long workloadScoreMs,
        int evidenceCount,
        String reason
    ) {
        public String indexName() {
            return "idx_" + table + "_" + String.join("_", columnsInOrder);
        }
        public String createStatement() {
            return String.format("CREATE INDEX %s ON %s (%s);",
                indexName(), table, String.join(", ", columnsInOrder));
        }
    }

    /**
     * Turn a list of workload-aggregated groups into ordered composite plans.
     * Groups below {@code minWorkloadMs} are skipped — they don't pay back
     * the write-amplification cost of a new index.
     */
    public List<Plan> plan(String connectionId, List<CandidateGroup> groups) {
        if (groups == null || groups.isEmpty()) return List.of();

        // Pre-load selectivity for the connection once; the API is cheap and
        // re-fetching per group would be O(N*M).
        Map<String, BigDecimal> selectivity = loadSelectivity(connectionId);

        List<Plan> plans = new ArrayList<>();
        for (CandidateGroup g : groups) {
            if (g.table == null) continue;
            if (g.workloadScoreMs < minWorkloadMs) {
                log.debug("Skipping {} composite plan: workload {}ms < min {}ms",
                    g.table, g.workloadScoreMs, minWorkloadMs);
                continue;
            }
            Plan p = build(g, selectivity);
            if (p != null) plans.add(p);
        }
        return plans;
    }

    private Plan build(CandidateGroup g, Map<String, BigDecimal> selectivity) {
        List<String> ordered = new ArrayList<>();

        // 1. Equality columns ordered by selectivity DESC. High-selectivity
        // columns first so each step of the B-tree descent prunes the most
        // candidate rows. Ties broken by column name for stable output.
        List<TableColumnRef> eq = new ArrayList<>(g.equalityColumns);
        eq.sort(Comparator
            .comparing((TableColumnRef r) -> selectivityOf(selectivity, r))
            .reversed()
            .thenComparing(TableColumnRef::getColumn));
        for (TableColumnRef r : eq) {
            if (ordered.size() >= maxColumns) break;
            if (!ordered.contains(r.getColumn())) ordered.add(r.getColumn());
        }

        // 2. Range column — pick the single highest-selectivity range column
        // (only one range column ever benefits from being on a B-tree).
        if (ordered.size() < maxColumns && !g.rangeColumns.isEmpty()) {
            TableColumnRef bestRange = g.rangeColumns.stream()
                .max(Comparator.comparing(r -> selectivityOf(selectivity, r)))
                .orElse(null);
            if (bestRange != null && !ordered.contains(bestRange.getColumn())) {
                ordered.add(bestRange.getColumn());
            }
        }

        // 3. ORDER BY suffix — only valid if every WHERE column is equality
        // AND no range column was added. Otherwise the sort cannot ride the
        // index ordering.
        boolean canAppendOrderBy = g.rangeColumns.isEmpty();
        if (canAppendOrderBy) {
            for (TableColumnRef r : g.orderByColumns) {
                if (ordered.size() >= maxColumns) break;
                if (!ordered.contains(r.getColumn())) ordered.add(r.getColumn());
            }
        }

        // 4. GROUP BY fallback — same rule as ORDER BY.
        if (canAppendOrderBy) {
            for (TableColumnRef r : g.groupByColumns) {
                if (ordered.size() >= maxColumns) break;
                if (!ordered.contains(r.getColumn())) ordered.add(r.getColumn());
            }
        }

        if (ordered.isEmpty()) return null;

        return new Plan(
            g.table,
            ordered,
            g.workloadScoreMs,
            g.evidenceCount,
            buildReason(g, ordered, canAppendOrderBy)
        );
    }

    private String buildReason(CandidateGroup g, List<String> ordered, boolean usedOrderBy) {
        StringBuilder r = new StringBuilder();
        r.append("Workload-weighted composite (")
         .append(g.evidenceCount).append(" contributing quer")
         .append(g.evidenceCount == 1 ? "y" : "ies").append(", ")
         .append(String.format("%.1fs total query time", g.workloadScoreMs / 1000.0))
         .append("). Column order: equality first (selectivity-ranked)");
        if (!g.rangeColumns.isEmpty()) r.append(", range last");
        if (usedOrderBy && !g.orderByColumns.isEmpty()) r.append(", ORDER BY suffix");
        r.append(". Suggested: (").append(String.join(", ", ordered)).append(").");
        return r.toString();
    }

    private Map<String, BigDecimal> loadSelectivity(String connectionId) {
        Map<String, BigDecimal> out = new HashMap<>();
        try {
            List<KeyColumnAnalysis> rows = keyColumnAnalysisRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId);
            for (KeyColumnAnalysis k : rows) {
                BigDecimal s = k.getSelectivity();
                if (s != null) {
                    String key = (k.getTableName() + "." + k.getColumnName()).toLowerCase(Locale.ROOT);
                    out.put(key, s);
                }
            }
        } catch (Exception e) {
            log.debug("KeyColumnAnalysis unavailable; selectivity defaults will be used: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Selectivity lookup with a sensible default. Higher = more selective.
     * Returns 0.5 if unknown (mid-range) so unknown columns don't get
     * pessimistically pushed to the back of the order.
     */
    private BigDecimal selectivityOf(Map<String, BigDecimal> selectivity, TableColumnRef ref) {
        if (ref == null) return BigDecimal.ZERO;
        String key = (ref.getTable() + "." + ref.getColumn()).toLowerCase(Locale.ROOT);
        return selectivity.getOrDefault(key, new BigDecimal("0.5"));
    }
}
