package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.IndexRecommendationEvidence;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.IndexRecommendationEvidenceRepository;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.service.index.HypotheticalCostEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Applies an {@link IndexRecommendationEntity} against the target connection
 * and measures the before/after benefit on contributing queries.
 *
 * Modes (mirroring how a DBA actually validates an index suggestion):
 *
 *   - {@link Mode#DRY_RUN}: zero writes. Uses HypoPG (Postgres) to install a
 *     virtual index in the session, EXPLAINs every contributing query, diffs
 *     the planner cost. Falls back to a plain EXPLAIN cost on connections
 *     without HypoPG (no opinion on "would the planner switch?", but the
 *     before-cost is still useful).
 *
 *   - {@link Mode#APPLY}: actually creates (or drops) the index. Postgres
 *     gets {@code CREATE INDEX CONCURRENTLY} / {@code DROP INDEX CONCURRENTLY}
 *     so the operation doesn't lock the table. Measurement is the planner
 *     cost delta on contributing queries — same metric as DRY_RUN, but now
 *     reflecting the real index.
 *
 *   - {@link Mode#APPLY_AND_MEASURE}: APPLY plus {@code EXPLAIN ANALYZE}
 *     before and after — gives wall-clock query times. Slowest mode; only
 *     run when the caller explicitly opts in (it does execute the
 *     contributing queries against production).
 *
 * APPLY and APPLY_AND_MEASURE require {@code confirmed=true} from the
 * caller — write operations don't happen by accident.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IndexRecommendationApplyService {

    private final IndexRecommendationRepository recommendationRepository;
    private final IndexRecommendationEvidenceRepository evidenceRepository;
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;
    private final java.util.Optional<HypotheticalCostEstimator> hypotheticalCostEstimator;

    public enum Mode {
        /** No writes. HypoPG virtual index + EXPLAIN cost diff. */
        DRY_RUN,
        /** Real CREATE/DROP INDEX (CONCURRENTLY on Postgres). Planner cost diff via EXPLAIN. */
        APPLY,
        /** APPLY plus EXPLAIN ANALYZE before/after — runs the contributing queries. */
        APPLY_AND_MEASURE
    }

    /**
     * Apply options that can't fit into the {@link Mode} enum without proliferating
     * variants. The default is the safe / production-friendly path; callers opt
     * out for dev workflows.
     */
    public record ApplyOptions(boolean concurrent) {
        public static ApplyOptions defaults() { return new ApplyOptions(true); }
    }

    /** Outcome of a single apply attempt. */
    public record ApplyResult(
        String recommendationId,
        String executedDdl,
        Mode mode,
        Status status,
        Double beforeCost,
        Double afterCost,
        Double costReductionPct,
        Double beforeWallTimeMs,
        Double afterWallTimeMs,
        Double wallTimeImprovementPct,
        List<SampleMeasurement> samples,
        String message,
        LocalDateTime executedAt
    ) {}

    public enum Status { OK, BLOCKED_NEEDS_CONFIRMATION, NOT_FOUND, NO_USABLE_SAMPLES, FAILED }

    /** Per-contributing-query measurement, surfaced so the caller can audit each one. */
    public record SampleMeasurement(
        String fingerprint,
        Double beforeCost,
        Double afterCost,
        Double beforeWallTimeMs,
        Double afterWallTimeMs,
        String error
    ) {}

    /**
     * Execute apply (or dry-run) for a recommendation.
     *
     * @param recommendationId  recommendation row id
     * @param mode              {@link Mode}
     * @param confirmed         must be true for APPLY / APPLY_AND_MEASURE — guard against accidental writes
     */
    @Transactional
    public ApplyResult apply(String recommendationId, Mode mode, boolean confirmed) {
        return apply(recommendationId, mode, confirmed, ApplyOptions.defaults());
    }

    @Transactional
    public ApplyResult apply(String recommendationId, Mode mode, boolean confirmed, ApplyOptions options) {
        Optional<IndexRecommendationEntity> opt = recommendationRepository.findById(recommendationId);
        if (opt.isEmpty()) {
            return new ApplyResult(recommendationId, null, mode, Status.NOT_FOUND,
                null, null, null, null, null, null, List.of(),
                "Recommendation not found", LocalDateTime.now());
        }
        IndexRecommendationEntity rec = opt.get();

        if ((mode == Mode.APPLY || mode == Mode.APPLY_AND_MEASURE) && !confirmed) {
            return new ApplyResult(rec.getId(), rec.getCreateStatement(), mode,
                Status.BLOCKED_NEEDS_CONFIRMATION, null, null, null, null, null, null, List.of(),
                "APPLY mode mutates the target database. Re-call with confirm=true.",
                LocalDateTime.now());
        }

        // Collect contributing queries first — if there's nothing measurable
        // we shouldn't be acquiring a target-DB connection at all. Cheaper
        // failure and clearer signal for the caller.
        List<IndexRecommendationEvidence> ev = evidenceRepository
            .findByRecommendationIdOrderByTotalExecTimeMsDesc(rec.getId(), PageRequest.of(0, 3));
        List<IndexRecommendationEvidence> usable = ev.stream()
            .filter(e -> e.getExampleSql() != null && !e.getExampleSql().isBlank())
            .filter(e -> !e.getExampleSql().contains("$1") && !e.getExampleSql().contains("?"))
            .toList();
        if (usable.isEmpty()) {
            return new ApplyResult(rec.getId(), rec.getCreateStatement(), mode,
                Status.NO_USABLE_SAMPLES, null, null, null, null, null, null, List.of(),
                "No literal-bearing contributing queries available for measurement.",
                LocalDateTime.now());
        }

        // Resolve target connection and dialect once.
        ConnectionRequest req;
        String dbType;
        try {
            req = credentialService.getDecryptedConnection(rec.getConnectionId());
            dbType = providerRegistry.getCanonicalName(req.getDbType());
        } catch (Exception e) {
            return failed(rec, mode, "Could not resolve connection: " + e.getMessage());
        }

        try (Connection c = connectionService.getConnection(rec.getConnectionId(), req)) {
            String ddl = rewriteForDialect(rec, dbType, mode, options);

            // --- Baseline measurement ----------------------------------------------
            List<SampleMeasurement> samples = new ArrayList<>();
            BeforeAfter before = measureAll(c, usable, mode == Mode.APPLY_AND_MEASURE);
            for (int i = 0; i < usable.size(); i++) {
                samples.add(new SampleMeasurement(
                    usable.get(i).getQueryFingerprint(),
                    before.costs.get(i),
                    null,
                    before.wallTimes.get(i),
                    null,
                    before.errors.get(i)
                ));
            }

            // --- DRY_RUN: install hypothetical index, measure, reset --------------
            // --- APPLY/APPLY_AND_MEASURE: run the actual DDL ----------------------
            if (mode == Mode.DRY_RUN) {
                if (!installHypothetical(c, dbType, rec)) {
                    return new ApplyResult(rec.getId(), ddl, mode, Status.FAILED,
                        avg(before.costs), null, null, avg(before.wallTimes), null, null, samples,
                        "DRY_RUN requires HypoPG on Postgres. Use mode=APPLY for connections without it.",
                        LocalDateTime.now());
                }
            } else {
                try (Statement s = c.createStatement()) {
                    log.info("Applying recommendation {} on {}: {}", rec.getId(), rec.getConnectionId(), ddl);
                    s.execute(ddl);
                }
            }

            // --- After measurement --------------------------------------------------
            BeforeAfter after = measureAll(c, usable, mode == Mode.APPLY_AND_MEASURE);
            for (int i = 0; i < usable.size(); i++) {
                SampleMeasurement s0 = samples.get(i);
                samples.set(i, new SampleMeasurement(
                    s0.fingerprint(),
                    s0.beforeCost(),
                    after.costs.get(i),
                    s0.beforeWallTimeMs(),
                    after.wallTimes.get(i),
                    s0.error() != null ? s0.error() : after.errors.get(i)
                ));
            }

            // Cleanup HypoPG in DRY_RUN so the session leaves no virtual index.
            if (mode == Mode.DRY_RUN && "postgres".equals(dbType)) {
                try (Statement s = c.createStatement()) {
                    s.execute("SELECT hypopg_reset()");
                } catch (Exception ignored) { }
            }

            Double bCost = avg(before.costs);
            Double aCost = avg(after.costs);
            Double bWall = avg(before.wallTimes);
            Double aWall = avg(after.wallTimes);

            // Mark APPLIED in the ledger so the candidate doesn't re-surface
            // in the next refresh cycle.
            if (mode == Mode.APPLY || mode == Mode.APPLY_AND_MEASURE) {
                rec.markAsApplied();
                if (aCost != null) rec.setHypopgAfterCost(aCost);
                if (bCost != null) rec.setHypopgBeforeCost(bCost);
                if (bCost != null && bCost > 0 && aCost != null) {
                    rec.setHypopgReductionPct(Math.max(-100.0,
                        Math.min(100.0, (bCost - aCost) / bCost * 100.0)));
                    rec.setHypopgEvaluatedAt(LocalDateTime.now());
                }
                recommendationRepository.save(rec);
            }

            return new ApplyResult(
                rec.getId(),
                ddl,
                mode,
                Status.OK,
                bCost,
                aCost,
                percentReduction(bCost, aCost),
                bWall,
                aWall,
                percentReduction(bWall, aWall),
                samples,
                buildMessage(mode, bCost, aCost, bWall, aWall),
                LocalDateTime.now()
            );

        } catch (Exception e) {
            log.warn("Apply failed for recommendation {}: {}", recommendationId, e.getMessage(), e);
            return failed(rec, mode, e.getMessage());
        }
    }

    private boolean installHypothetical(Connection c, String dbType, IndexRecommendationEntity rec) {
        if (!"postgres".equals(dbType)) return false;
        if (hypotheticalCostEstimator == null || hypotheticalCostEstimator.isEmpty()) return false;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_extension WHERE extname = 'hypopg'");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return false;
        } catch (Exception e) {
            return false;
        }
        // HypoPG accepts the full CREATE INDEX DDL — strip the index name
        // (it picks its own internal one) and any trailing semicolon.
        String hypopgDdl = stripIndexName(rec.getCreateStatement());
        try (PreparedStatement ps = c.prepareStatement("SELECT hypopg_create_index(?)")) {
            ps.setString(1, hypopgDdl);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.warn("hypopg_create_index failed for {}: {}", rec.getId(), e.getMessage());
            return false;
        }
    }

    /** Strip the explicit index name HypoPG doesn't want and the trailing semicolon. */
    private String stripIndexName(String ddl) {
        if (ddl == null) return null;
        String s = ddl.trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        // CREATE INDEX <name> ON ...  →  CREATE INDEX ON ...
        return s.replaceFirst("(?i)^CREATE\\s+INDEX\\s+\\w+\\s+ON\\b", "CREATE INDEX ON");
    }

    /**
     * Rewrite the DDL for safety:
     *   - Postgres CREATE INDEX → CONCURRENTLY (no table lock) by default.
     *     Opt out via {@code options.concurrent=false} — useful on small dev
     *     tables, or when the calling DBA has already drained long-running
     *     transactions out of the pool. CONCURRENTLY waits for every
     *     pre-existing transaction to finish, so on a busy pool it can block
     *     indefinitely.
     *   - Postgres DROP INDEX → CONCURRENTLY by default; same opt-out.
     *   - MySQL: leave as-is. Online DDL is implicit on InnoDB for index
     *     creation; DROP is metadata-only and fast.
     */
    private String rewriteForDialect(IndexRecommendationEntity rec, String dbType, Mode mode, ApplyOptions options) {
        String ddl = rec.getCreateStatement();
        if (ddl == null) return null;
        if (mode == Mode.DRY_RUN) return ddl;
        if (!"postgres".equals(dbType)) return ddl;
        if (!options.concurrent()) return ddl;
        // Postgres only: insert CONCURRENTLY.
        if (rec.getKind() == IndexRecommendationEntity.Kind.CREATE_INDEX) {
            return ddl.replaceFirst("(?i)^CREATE\\s+INDEX\\b(?!\\s+CONCURRENTLY)", "CREATE INDEX CONCURRENTLY");
        }
        if (rec.getKind() == IndexRecommendationEntity.Kind.DROP_INDEX) {
            return ddl.replaceFirst("(?i)^DROP\\s+INDEX\\b(?!\\s+CONCURRENTLY)", "DROP INDEX CONCURRENTLY");
        }
        return ddl;
    }

    /** Collected before/after costs and wall-clock times across all samples. */
    private static final class BeforeAfter {
        final List<Double> costs = new ArrayList<>();
        final List<Double> wallTimes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private BeforeAfter measureAll(Connection c, List<IndexRecommendationEvidence> samples, boolean withAnalyze) {
        BeforeAfter out = new BeforeAfter();
        for (IndexRecommendationEvidence sample : samples) {
            try {
                double cost = explainCost(c, sample.getExampleSql());
                out.costs.add(cost);
                Double wall = null;
                if (withAnalyze) {
                    wall = explainAnalyzeWallTime(c, sample.getExampleSql());
                }
                out.wallTimes.add(wall);
                out.errors.add(null);
            } catch (Exception e) {
                out.costs.add(null);
                out.wallTimes.add(null);
                out.errors.add(e.getMessage());
            }
        }
        return out;
    }

    private double explainCost(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN (FORMAT JSON) " + sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("EXPLAIN returned no row");
            String json = rs.getString(1);
            // Parse Plan."Total Cost" with a tiny scanner; avoid pulling a JSON
            // dep into this file. The Plan stanza is always the first object.
            int idx = json.indexOf("\"Total Cost\"");
            if (idx < 0) throw new IllegalStateException("EXPLAIN JSON missing Total Cost");
            int colon = json.indexOf(':', idx);
            int end = json.indexOf(',', colon);
            if (end < 0) end = json.indexOf('}', colon);
            return Double.parseDouble(json.substring(colon + 1, end).trim());
        }
    }

    private Double explainAnalyzeWallTime(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN (ANALYZE, FORMAT JSON) " + sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            String json = rs.getString(1);
            int idx = json.indexOf("\"Execution Time\"");
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx);
            int end = json.indexOf(',', colon);
            if (end < 0) end = json.indexOf('}', colon);
            return Double.parseDouble(json.substring(colon + 1, end).trim());
        }
    }

    private static Double avg(Collection<Double> xs) {
        return xs.stream().filter(java.util.Objects::nonNull).mapToDouble(d -> d).average().stream().boxed().findFirst().orElse(null);
    }

    private static Double percentReduction(Double before, Double after) {
        if (before == null || after == null || before <= 0) return null;
        return Math.max(-100.0, Math.min(100.0, (before - after) / before * 100.0));
    }

    private String buildMessage(Mode mode, Double bCost, Double aCost, Double bWall, Double aWall) {
        StringBuilder sb = new StringBuilder();
        sb.append(mode).append(" complete");
        if (bCost != null && aCost != null) {
            Double pct = percentReduction(bCost, aCost);
            sb.append(String.format(" — planner cost %s%.1f%% (%.0f → %.0f)",
                pct != null && pct >= 0 ? "−" : "+",
                pct != null ? Math.abs(pct) : 0, bCost, aCost));
        }
        if (bWall != null && aWall != null) {
            Double pct = percentReduction(bWall, aWall);
            sb.append(String.format(", wall-time %s%.1f%% (%.1fms → %.1fms)",
                pct != null && pct >= 0 ? "−" : "+",
                pct != null ? Math.abs(pct) : 0, bWall, aWall));
        }
        return sb.toString();
    }

    private ApplyResult failed(IndexRecommendationEntity rec, Mode mode, String msg) {
        return new ApplyResult(rec.getId(),
            rec.getCreateStatement(),
            mode, Status.FAILED,
            null, null, null, null, null, null, List.of(),
            msg,
            LocalDateTime.now());
    }
}
