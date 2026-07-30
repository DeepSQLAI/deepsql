package com.dbaagent.service.index.postgres;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.index.HypotheticalCostEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * Real implementation of {@link HypotheticalCostEstimator} backed by HypoPG
 * (https://github.com/HypoPG/hypopg). Mirrors the Dexter / pganalyze workflow:
 *
 *   1. Confirm the {@code hypopg} extension is installed on the target DB.
 *      If not, return {@link Optional#empty()} — the rest of the pipeline
 *      treats absence of opinion as "fall back to the heuristic score."
 *   2. Capture each sample query's planner-reported total cost via
 *      {@code EXPLAIN (FORMAT JSON)}.
 *   3. Install a hypothetical version of the recommended index with
 *      {@code SELECT hypopg_create_index(<ddl>)}. HypoPG's index is virtual —
 *      it consumes no storage, blocks nothing, and is session-scoped.
 *   4. Re-run EXPLAIN; sum the new costs.
 *   5. {@code hypopg_reset()} to clean the session.
 *
 * The cost reduction tells the planner's opinion of the candidate. A
 * positive {@code reductionPct} proves the planner would actually use the
 * recommended index — distinguishing "would-be" wins from "the heuristic
 * says yes but the planner wouldn't switch."
 *
 * MySQL has no equivalent extension; the existing
 * {@link HypotheticalCostEstimator} default ({@code Optional.empty()}) is
 * the right behavior there.
 *
 * Opt-in. Activate via {@code index-recommendations.hypopg.enabled=true}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PostgresHypotheticalCostEstimator implements HypotheticalCostEstimator {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    @Value("${index-recommendations.hypopg.enabled:true}")
    private boolean enabled;

    /** Hard upper bound to keep validation cheap during refresh cycles. */
    @Value("${index-recommendations.hypopg.max-samples:3}")
    private int maxSamples;

    @Override
    public Optional<CostDelta> estimate(
        String connectionId,
        CandidateIndex candidate,
        List<String> sampleQueries
    ) {
        if (!enabled || candidate == null || sampleQueries == null || sampleQueries.isEmpty()) {
            return Optional.empty();
        }

        ConnectionRequest req;
        try {
            req = credentialService.getDecryptedConnection(connectionId);
        } catch (Exception e) {
            log.debug("HypoPG estimate: cannot resolve connection {}: {}", connectionId, e.getMessage());
            return Optional.empty();
        }

        String dbType = providerRegistry.getCanonicalName(req.getDbType());
        if (!"postgres".equals(dbType)) {
            // No MySQL equivalent. Caller handles Optional.empty().
            return Optional.empty();
        }

        try (Connection c = connectionService.getConnection(connectionId, req)) {
            if (!hasHypoPgExtension(c)) {
                log.debug("HypoPG extension not installed on connection {} — skipping estimate", connectionId);
                return Optional.empty();
            }

            List<String> usable = sampleQueries.stream()
                .filter(s -> s != null && !s.isBlank())
                // Skip parameterized normalised queries — EXPLAIN can't bind ?
                .filter(s -> !s.contains("$1") && !s.contains("?"))
                .limit(Math.max(1, maxSamples))
                .toList();
            if (usable.isEmpty()) {
                log.debug("HypoPG estimate: no usable (literal-bearing) sample queries for {}", candidate);
                return Optional.empty();
            }

            double beforeTotal = 0;
            int beforeCount = 0;
            for (String sql : usable) {
                Optional<Double> cost = explainTotalCost(c, sql);
                if (cost.isPresent()) {
                    beforeTotal += cost.get();
                    beforeCount++;
                }
            }
            if (beforeCount == 0) {
                log.debug("HypoPG estimate: every sample EXPLAIN failed for {}", candidate);
                return Optional.empty();
            }

            String ddl = buildCreateIndexDdl(candidate);
            if (!createHypotheticalIndex(c, ddl)) {
                return Optional.empty();
            }

            double afterTotal = 0;
            for (String sql : usable) {
                Optional<Double> cost = explainTotalCost(c, sql);
                if (cost.isPresent()) afterTotal += cost.get();
            }

            resetHypoPg(c); // best-effort

            // Guard against pathological "0 → 0" or "before went negative" plans.
            if (beforeTotal <= 0) return Optional.empty();
            double reductionPct = Math.max(-100.0,
                Math.min(100.0, (beforeTotal - afterTotal) / beforeTotal * 100.0));
            return Optional.of(new CostDelta(beforeTotal, afterTotal, reductionPct));

        } catch (Exception e) {
            log.warn("HypoPG estimate failed for {} candidate={}: {}", connectionId, candidate, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean hasHypoPgExtension(Connection c) {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'hypopg'")) {
            return rs.next();
        } catch (SQLException e) {
            log.debug("Could not probe hypopg extension: {}", e.getMessage());
            return false;
        }
    }

    private Optional<Double> explainTotalCost(Connection c, String sql) {
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN (FORMAT JSON) " + sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            String json = rs.getString(1);
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray() || arr.isEmpty()) return Optional.empty();
            JsonNode plan = arr.get(0).get("Plan");
            if (plan == null) return Optional.empty();
            JsonNode tc = plan.get("Total Cost");
            if (tc == null) return Optional.empty();
            return Optional.of(tc.asDouble());
        } catch (Exception e) {
            log.debug("EXPLAIN failed for HypoPG sample (likely parameter placeholder): {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean createHypotheticalIndex(Connection c, String ddl) {
        try (PreparedStatement ps = c.prepareStatement("SELECT hypopg_create_index(?)")) {
            ps.setString(1, ddl);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("hypopg_create_index('{}') failed: {}", ddl, e.getMessage());
            return false;
        }
    }

    private void resetHypoPg(Connection c) {
        try (Statement s = c.createStatement()) {
            s.execute("SELECT hypopg_reset()");
        } catch (SQLException ignored) {
            // Session-scoped anyway; not worth surfacing.
        }
    }

    /**
     * Build the CREATE INDEX DDL HypoPG expects. Partial-index candidates
     * include a WHERE clause; everything else is the standard form.
     */
    private String buildCreateIndexDdl(CandidateIndex candidate) {
        StringBuilder sb = new StringBuilder("CREATE INDEX ON ");
        sb.append(candidate.table()).append(" (")
          .append(String.join(", ", candidate.columns())).append(")");
        if (candidate.partial() && candidate.partialPredicate() != null
            && !candidate.partialPredicate().isBlank()) {
            sb.append(" WHERE ").append(candidate.partialPredicate());
        }
        return sb.toString();
    }
}
