package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Customer dimension — one row per distinct tenant value seen on a connection.
 *
 * {@code customerId} is the raw literal pulled from the configured tenant
 * column (e.g. {@code customer_id}). {@code customerName} is resolved lazily via
 * a lookup against the target database (see
 * {@link ConnectionAnalyticsConfig}) and cached here so we do not re-query the
 * customer's database every analysis cycle.
 */
@Entity
@Table(name = "slow_query_customer", indexes = {
    @Index(name = "idx_slow_query_customer_conn", columnList = "connection_id, last_seen_at")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_slow_query_customer", columnNames = {"connection_id", "customer_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowQueryCustomer {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    /** Raw tenant literal extracted from the query's WHERE clause. */
    @Column(name = "customer_id", nullable = false, length = 128)
    private String customerId;

    /** Human-readable name resolved from the target DB; null until resolved. */
    @Column(name = "customer_name", length = 512)
    private String customerName;

    /** Which column the id came from (e.g. {@code customer_id}). */
    @Column(name = "tenant_column", length = 128)
    private String tenantColumn;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "name_resolved_at")
    private LocalDateTime nameResolvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (firstSeenAt == null) {
            firstSeenAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }
}
