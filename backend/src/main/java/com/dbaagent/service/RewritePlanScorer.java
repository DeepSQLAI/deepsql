package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ExplainPlanNode;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.ExplainPlanProvider;
import com.dbaagent.util.SqlStatementSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Scores a candidate rewrite from its ESTIMATED execution plan WITHOUT running
 * the query. Runs plain EXPLAIN (FORMAT JSON / FORMAT=JSON — never ANALYZE),
 * parses the plan tree via the existing provider, and summarizes the signals
 * that matter for picking the best rewrite:
 *   - estimated total cost (the planner's own composite)
 *   - total estimated rows scanned across the plan (cardinality)
 *   - number of full/sequential scans (i.e. an index NOT kicking in)
 *
 * No execution, no AI, no caching — safe to call for many candidates quickly.
 * Deliberately has NO dependency on QueryOptimizationService (avoids a cycle).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RewritePlanScorer {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    /** Plan-derived signals for one candidate. Lower cost/rows/fullScans is better. */
    public record PlanScore(
        boolean valid,
        double estimatedCost,
        long estimatedRows,
        int fullScans,
        int nodeCount,
        String error
    ) {
        public static PlanScore invalid(String error) {
            return new PlanScore(false, Double.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, 0, error);
        }
    }

    /**
     * EXPLAIN (no execute) a candidate and summarize its plan. SET preamble
     * statements (SET @var = …) are applied to the session first so the SELECT
     * can be planned, then the SELECT is EXPLAINed.
     */
    public PlanScore score(String connectionId, String sql) {
        if (sql == null || sql.isBlank()) return PlanScore.invalid("empty sql");
        try {
            ConnectionRequest conn = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(conn.getDbType());
            DatabaseDialect dialect = providerRegistry.getDialect(dbType);
            ExplainPlanProvider provider = dialect.explainPlan();
            if (provider == null) return PlanScore.invalid("no explain provider for " + dbType);

            boolean hashIsComment = !"postgres".equalsIgnoreCase(dbType);
            String[] parts = SqlStatementSplitter.splitSetPreamble(sql, hashIsComment);
            String selectSql = parts[parts.length - 1];

            try (Connection c = connectionService.getConnection(connectionId, conn);
                 Statement st = c.createStatement()) {
                st.setQueryTimeout(15);
                for (int i = 0; i < parts.length - 1; i++) {
                    st.execute(parts[i]); // SET @var = …  (cheap, no row work)
                }
                // analyze=false → estimate-only plan, the query is NOT executed.
                List<Map<String, Object>> results = provider.executeExplain(c, selectSql, false);
                ExplainPlanNode tree = provider.parseExplainResult(results);
                if (tree == null) return PlanScore.invalid("empty plan");
                return summarize(tree);
            }
        } catch (Exception e) {
            log.debug("Plan scoring failed: {}", e.getMessage());
            return PlanScore.invalid(e.getMessage());
        }
    }

    private PlanScore summarize(ExplainPlanNode tree) {
        long[] rows = {0};
        int[] fullScans = {0};
        int[] nodes = {0};
        walk(tree, rows, fullScans, nodes);
        double cost = tree.getTotalCost() != null ? tree.getTotalCost() : Double.MAX_VALUE;
        return new PlanScore(true, cost, rows[0], fullScans[0], nodes[0], null);
    }

    private void walk(ExplainPlanNode n, long[] rows, int[] fullScans, int[] nodes) {
        if (n == null) return;
        nodes[0]++;
        if (n.getPlanRows() != null) rows[0] += n.getPlanRows();
        String type = n.getNodeType() == null ? "" : n.getNodeType().toLowerCase();
        String access = n.getAccessType() == null ? "" : n.getAccessType().toLowerCase();
        boolean fullScan = n.getKey() == null && (
            type.contains("seq scan") || type.contains("table scan") || type.contains("full")
            || access.equals("all"));
        if (fullScan) fullScans[0]++;
        if (n.getChildren() != null) {
            for (ExplainPlanNode child : n.getChildren()) {
                walk(child, rows, fullScans, nodes);
            }
        }
    }
}
