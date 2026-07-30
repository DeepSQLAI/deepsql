package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per slow query per daily analysis run — the per-query time series.
 *
 * The query identity is {@code fingerprint}: the MD5 of the normalized query
 * (see {@link com.dbaagent.util.QueryNormalizer#generateFingerprint}). It is
 * deterministic, so the same query keeps one identity across all 30 days of
 * runs — join to {@link QueryFingerprint} on {@code (connectionId, fingerprint)}.
 *
 * pg_stat_statements / performance_schema report counters cumulative since the
 * last reset, so each run stores BOTH the raw cumulative value and the delta
 * versus the previous run. {@code counterReset} flags the case where the
 * source stats were reset between runs (current &lt; previous).
 */
@Entity
@Table(name = "slow_query_run", indexes = {
    @Index(name = "idx_slow_query_run_timeline", columnList = "connection_id, fingerprint, analyzed_on"),
    @Index(name = "idx_slow_query_run_regression", columnList = "connection_id, analyzed_on, regression_factor")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowQueryRun {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    /** FK to the {@code slow_query_history} run header that produced this row (nullable). */
    @Column(name = "analysis_run_id")
    private String analysisRunId;

    /** Stable query identity — MD5 of the normalized query. */
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "analyzed_on", nullable = false)
    private LocalDate analyzedOn;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    // ── cumulative counters (raw, since the source's last reset) ────────────
    @Column(name = "calls_cumulative")
    private Long callsCumulative;

    @Column(name = "total_exec_ms_cumulative")
    private Double totalExecMsCumulative;

    // ── delta for this period (vs the previous run) ─────────────────────────
    @Column(name = "calls_delta")
    private Long callsDelta;

    @Column(name = "total_exec_ms_delta")
    private Double totalExecMsDelta;

    @Column(name = "mean_exec_ms")
    private Double meanExecMs;

    @Column(name = "max_exec_ms")
    private Double maxExecMs;

    @Column(name = "p95_exec_ms")
    private Double p95ExecMs;

    @Column(name = "rows_examined_delta")
    private Long rowsExaminedDelta;

    @Column(name = "rows_sent_delta")
    private Long rowsSentDelta;

    // ── regression tracking ─────────────────────────────────────────────────
    /** FK to the previous {@code slow_query_run} for this query. */
    @Column(name = "prev_run_id")
    private String prevRunId;

    /** meanExecMs / previous.meanExecMs — &gt; 1.0 means the query got slower. */
    @Column(name = "regression_factor")
    private Double regressionFactor;

    @Column(name = "counter_reset", nullable = false)
    private boolean counterReset;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (capturedAt == null) {
            capturedAt = createdAt;
        }
    }
}
