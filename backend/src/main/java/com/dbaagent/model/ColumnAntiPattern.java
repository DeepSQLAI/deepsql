package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores detected anti-patterns with actionable recommendations.
 * Helps DBAs identify and fix performance issues proactively.
 */
@Entity
@Table(name = "column_anti_pattern", indexes = {
    @Index(name = "idx_col_anti_pattern_connection", columnList = "connectionId"),
    @Index(name = "idx_col_anti_pattern_column", columnList = "connectionId,tableName,columnName"),
    @Index(name = "idx_col_anti_pattern_severity", columnList = "severity"),
    @Index(name = "idx_col_anti_pattern_status", columnList = "status"),
    @Index(name = "idx_col_anti_pattern_type", columnList = "patternType")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_col_anti_pattern",
                      columnNames = {"connectionId", "tableName", "columnName", "patternType"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnAntiPattern {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String tableName;

    @Column(nullable = false)
    private String columnName;

    // Anti-pattern classification
    @Column(nullable = false, length = 100)
    private String patternType; // UNINDEXED_FILTER, LOW_CARDINALITY_GROUP_BY, etc.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    // Problem description
    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    // Evidence
    @Column(nullable = false)
    @Builder.Default
    private Integer evidenceCount = 1;

    @Column(columnDefinition = "TEXT")
    private String sampleQueryIds; // JSON array of query IDs

    // Impact metrics
    @Column
    private Integer affectedQueriesCount;

    @Column
    private Long totalExecutionTimeMs;

    @Column(precision = 5, scale = 2)
    private BigDecimal estimatedImpactScore;

    // Recommendation
    @Column(columnDefinition = "TEXT")
    private String recommendation;

    @Column(columnDefinition = "TEXT")
    private String suggestedIndex;

    // Status
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column
    private LocalDateTime acknowledgedAt;

    @Column
    private String acknowledgedBy;

    @Column
    private LocalDateTime resolvedAt;

    // Timestamps
    @Column(nullable = false)
    private LocalDateTime detectedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (detectedAt == null) {
            detectedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum Status {
        ACTIVE,
        ACKNOWLEDGED,
        RESOLVED,
        FALSE_POSITIVE
    }
}
