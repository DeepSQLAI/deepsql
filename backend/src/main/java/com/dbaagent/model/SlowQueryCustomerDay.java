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
 * Per-query × per-customer × per-day rollup, derived from {@link SlowQuerySample}.
 *
 * This is the table that answers "did query X regress for customer Y over the
 * last 30 days" — {@code regressionFactor} is {@code meanExecMs} divided by the
 * previous day's mean for the same (query, customer) pair.
 */
@Entity
@Table(name = "slow_query_customer_day", indexes = {
    @Index(name = "idx_slow_query_customer_day_timeline",
        columnList = "connection_id, fingerprint, customer_id, day")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_slow_query_customer_day",
        columnNames = {"connection_id", "fingerprint", "customer_id", "day"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowQueryCustomerDay {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    /** Stable query identity — MD5 of the normalized query. */
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "customer_id", nullable = false, length = 128)
    private String customerId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "sample_count", nullable = false)
    private long sampleCount;

    @Column(name = "mean_exec_ms")
    private Double meanExecMs;

    @Column(name = "max_exec_ms")
    private Double maxExecMs;

    @Column(name = "total_exec_ms")
    private Double totalExecMs;

    @Column(name = "prev_day_mean_ms")
    private Double prevDayMeanMs;

    /** meanExecMs / prevDayMeanMs — &gt; 1.0 means slower for this customer. */
    @Column(name = "regression_factor")
    private Double regressionFactor;

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
    }
}
