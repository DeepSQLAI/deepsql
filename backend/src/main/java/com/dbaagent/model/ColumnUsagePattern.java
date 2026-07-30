package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores detailed breakdown of each column usage occurrence.
 * Enables drill-down analysis into specific queries using each column.
 */
@Entity
@Table(name = "column_usage_pattern", indexes = {
    @Index(name = "idx_col_usage_connection", columnList = "connectionId"),
    @Index(name = "idx_col_usage_column", columnList = "connectionId,tableName,columnName"),
    @Index(name = "idx_col_usage_type", columnList = "usageType"),
    @Index(name = "idx_col_usage_source", columnList = "querySource"),
    @Index(name = "idx_col_usage_hash", columnList = "queryHash"),
    @Index(name = "idx_col_usage_created", columnList = "createdAt")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_col_usage_pattern",
                      columnNames = {"connectionId", "tableName", "columnName", "queryHash", "usageType"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnUsagePattern {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String tableName;

    @Column(nullable = false)
    private String columnName;

    // Usage context
    @Column(nullable = false, length = 50)
    private String usageType; // JOIN, WHERE, GROUP_BY, ORDER_BY, SELECT

    @Column(length = 100)
    private String operation; // =, IN, LIKE, BETWEEN, etc.

    // Query source
    @Column(nullable = false, length = 50)
    private String querySource; // SLOW_QUERY, LINEAGE, PERFORMANCE_HISTORY

    @Column
    private String queryId; // Reference to source query entity

    @Column(columnDefinition = "TEXT")
    private String queryText;

    @Column(length = 64)
    private String queryHash; // For deduplication

    // Query metadata
    @Column(precision = 10, scale = 2)
    private BigDecimal queryExecutionTimeMs;

    @Column
    private LocalDateTime queryFirstSeen;

    @Column
    private LocalDateTime queryLastSeen;

    @Column(nullable = false)
    @Builder.Default
    private Integer occurrenceCount = 1;

    // Timestamps
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
