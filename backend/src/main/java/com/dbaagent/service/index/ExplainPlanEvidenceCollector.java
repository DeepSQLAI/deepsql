package com.dbaagent.service.index;

import com.dbaagent.model.ExplainPlanNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Walks a parsed {@link ExplainPlanNode} tree and emits index-relevant
 * findings — full table scans, expensive nested loops, MySQL filesort
 * tags. Replaces the legacy regex {@code "table scan on (\\w+)"} pattern in
 * the index advisor, which never matched real plans (Postgres emits
 * {@code Seq Scan}, MySQL emits {@code Full Table Scan}).
 *
 * Findings are workload-weighted by {@code actualTotalTime * actualLoops}
 * when EXPLAIN ANALYZE data is present, so a Seq Scan that runs once on a
 * hot path outranks one that runs once on a cold path.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ExplainPlanEvidenceCollector {

    /** Postgres Seq Scan / MySQL Full Table Scan with > this many actual rows. */
    private static final long SEQ_SCAN_ROW_THRESHOLD = 1_000L;

    /**
     * Findings emitted by a single plan walk.
     */
    public record Finding(
        Kind kind,
        String tableName,
        long actualRows,
        double actualTotalTimeMs,
        String description
    ) {
        public enum Kind {
            /** Full table scan on a real table — index on filter columns helps. */
            FULL_TABLE_SCAN,
            /** Nested loop with high actual rows on the inner side — index on the JOIN key helps. */
            EXPENSIVE_NESTED_LOOP,
            /** MySQL Using filesort — index ordered by the ORDER BY column helps. */
            USING_FILESORT,
            /** MySQL Using temporary — usually means GROUP BY without index. */
            USING_TEMPORARY
        }
    }

    /**
     * Walk a parsed plan tree and emit findings. Safe to call with a null
     * root; returns an empty list.
     */
    public List<Finding> collect(ExplainPlanNode root) {
        List<Finding> out = new ArrayList<>();
        if (root == null) return out;
        collectRecursive(root, out);
        return out;
    }

    private void collectRecursive(ExplainPlanNode node, List<Finding> out) {
        if (node == null) return;

        String nodeType = node.getNodeType();
        String accessType = node.getAccessType();
        String extra = node.getExtra();
        long actualRows = node.getActualRows() == null ? 0L : node.getActualRows();
        double actualTotalTime = node.getActualTotalTime() == null ? 0.0 : node.getActualTotalTime();
        String table = firstNonBlank(node.getTableName(), node.getAliasName());

        // ---- Full table scan ------------------------------------------------
        boolean isPostgresSeqScan = nodeType != null && nodeType.equalsIgnoreCase("Seq Scan");
        boolean isMySqlFullScan   = accessType != null && accessType.equalsIgnoreCase("ALL");
        if ((isPostgresSeqScan || isMySqlFullScan)
            && table != null
            && (actualRows >= SEQ_SCAN_ROW_THRESHOLD || node.getActualRows() == null)) {
            out.add(new Finding(
                Finding.Kind.FULL_TABLE_SCAN,
                table.toLowerCase(Locale.ROOT),
                actualRows,
                actualTotalTime,
                String.format("%s on %s (actualRows=%d, time=%.1fms)",
                    isPostgresSeqScan ? "Seq Scan" : "Full Table Scan",
                    table, actualRows, actualTotalTime)
            ));
        }

        // ---- Expensive nested loop -----------------------------------------
        // A Nested Loop is fine when the inner side is tiny; it's pathological
        // when the inner side scans many rows for each outer row.
        if (nodeType != null && nodeType.equalsIgnoreCase("Nested Loop")
            && node.getChildren() != null && !node.getChildren().isEmpty()) {
            for (ExplainPlanNode child : node.getChildren()) {
                long childRows = child.getActualRows() == null ? 0L : child.getActualRows();
                int loops = child.getActualLoops() == null ? 1 : child.getActualLoops();
                if (childRows * loops >= 10_000) {
                    String childTable = firstNonBlank(child.getTableName(), child.getAliasName());
                    if (childTable != null) {
                        out.add(new Finding(
                            Finding.Kind.EXPENSIVE_NESTED_LOOP,
                            childTable.toLowerCase(Locale.ROOT),
                            childRows * loops,
                            child.getActualTotalTime() == null ? 0.0 : child.getActualTotalTime() * loops,
                            String.format("Nested Loop with %d×%d rows on %s — JOIN-key index would help",
                                childRows, loops, childTable)
                        ));
                    }
                }
            }
        }

        // ---- MySQL: Using filesort -----------------------------------------
        if (extra != null && extra.toLowerCase(Locale.ROOT).contains("using filesort")
            && table != null) {
            out.add(new Finding(
                Finding.Kind.USING_FILESORT,
                table.toLowerCase(Locale.ROOT),
                actualRows,
                actualTotalTime,
                "Using filesort on " + table + " — ORDER BY column index would help"
            ));
        }

        // ---- MySQL: Using temporary ---------------------------------------
        if (extra != null && extra.toLowerCase(Locale.ROOT).contains("using temporary")
            && table != null) {
            out.add(new Finding(
                Finding.Kind.USING_TEMPORARY,
                table.toLowerCase(Locale.ROOT),
                actualRows,
                actualTotalTime,
                "Using temporary on " + table + " — GROUP BY column index would help"
            ));
        }

        // Recurse
        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                collectRecursive(child, out);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
