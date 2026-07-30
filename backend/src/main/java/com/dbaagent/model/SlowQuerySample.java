package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One literal-bearing slow execution captured from the slow query log.
 *
 * pg_stat_statements / performance_schema strip literals, so per-customer
 * attribution can only come from sources that keep them. The slow-log
 * ingestion pipeline writes one row here per slow execution, with the tenant
 * id extracted from the WHERE clause via the connection's configured tenant
 * column.
 *
 * {@code fingerprint} ties the sample back to the same stable query identity
 * used by {@link SlowQueryRun}, so the aggregate time series and the
 * per-customer samples join cleanly.
 */
@Entity
@Table(name = "slow_query_sample", indexes = {
    @Index(name = "idx_slow_query_sample_query", columnList = "connection_id, fingerprint, captured_at"),
    @Index(name = "idx_slow_query_sample_customer", columnList = "connection_id, customer_id, captured_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowQuerySample {

    /** Where the literal-bearing sample came from. */
    public enum Source {
        SLOW_LOG,
        PERF_SCHEMA_SAMPLE,
        ACTIVE_QUERY
    }

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    /**
     * Stable query identity — the canonical 16-char fingerprint
     * ({@link com.dbaagent.service.QueryFingerprintService#computeCanonicalFingerprint}
     * over the normalized query). Must match slow_query_run, query_fingerprints,
     * and the queryId surfaced by `analyze`/`latest` so `samples <id>` resolves.
     */
    @Column(nullable = false, length = 64)
    private String fingerprint;

    /** Tenant literal extracted from the WHERE clause; null when not tenant-scoped. */
    @Column(name = "customer_id", length = 128)
    private String customerId;

    /** The query text WITH literals, as it appeared in the slow log. */
    @Column(name = "raw_sql", columnDefinition = "TEXT")
    private String rawSql;

    @Column(name = "exec_ms")
    private Double execMs;

    @Column(name = "rows_examined")
    private Long rowsExamined;

    @Column(name = "rows_sent")
    private Long rowsSent;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Source source;

    @Column(name = "ingested_at", nullable = false)
    private LocalDateTime ingestedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (ingestedAt == null) {
            ingestedAt = LocalDateTime.now();
        }
        if (capturedAt == null) {
            capturedAt = ingestedAt;
        }
        if (source == null) {
            source = Source.SLOW_LOG;
        }
    }
}
