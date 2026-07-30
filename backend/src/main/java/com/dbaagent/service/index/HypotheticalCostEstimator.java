package com.dbaagent.service.index;

import java.util.List;
import java.util.Optional;

/**
 * Optional cost-delta validator for a candidate index, before it gets
 * persisted as a recommendation.
 *
 * On Postgres this is implementable via the HypoPG extension — create the
 * hypothetical index, run EXPLAIN, diff the {@code Total Cost} against the
 * pre-hypothesis plan. Dexter and pganalyze both use it. MySQL has no
 * equivalent; a real implementation there would have to settle for
 * {@code EXPLAIN FORMAT=JSON} cost estimates (much rougher).
 *
 * V1 of the DBA-grade rewrite stubs the interface — no implementations are
 * registered, so the service-level optional plumbing is a no-op. A follow-up
 * PR can wire up a Postgres impl without churn through the calling code.
 */
public interface HypotheticalCostEstimator {

    /**
     * Estimate the cost reduction (in plan cost units) if {@code candidate}
     * existed against the workload represented by the supplied sample SQL.
     *
     * @return empty when the estimator can't run (extension missing, MySQL
     *         connection, network failure, etc.) — callers must treat
     *         missing as "no opinion" and fall back to the heuristic score.
     */
    Optional<CostDelta> estimate(
        String connectionId,
        CandidateIndex candidate,
        List<String> sampleQueries
    );

    /** What we'd hypothesise. */
    record CandidateIndex(
        String table,
        List<String> columns,
        boolean partial,
        String partialPredicate
    ) {}

    /** Cost delta result. {@code reductionPct} in [0..100]. */
    record CostDelta(
        double beforeCost,
        double afterCost,
        double reductionPct
    ) {}
}
