package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores aggregated importance scores and usage statistics per column.
 * Tracks column usage across JOINs, WHERE clauses, GROUP BY, and ORDER BY operations
 * to identify key columns in the database.
 */
@Entity
@Table(name = "key_column_analysis", indexes = {
    @Index(name = "idx_key_col_connection", columnList = "connectionId"),
    @Index(name = "idx_key_col_score", columnList = "connectionId,importanceScore"),
    @Index(name = "idx_key_col_table", columnList = "connectionId,tableName"),
    @Index(name = "idx_key_col_anti_patterns", columnList = "connectionId,hasAntiPatterns"),
    @Index(name = "idx_key_col_analyzed_at", columnList = "analyzedAt")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_key_col_analysis", columnNames = {"connectionId", "tableName", "columnName"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyColumnAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String tableName;

    @Column(nullable = false)
    private String columnName;

    // Usage counts by type
    @Column(nullable = false)
    @Builder.Default
    private Integer joinCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer whereCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer groupByCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer orderByCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalUsageCount = 0;

    // Weighted importance score (0-100)
    @Column(precision = 5, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal importanceScore = BigDecimal.ZERO;

    // Query source breakdown
    @Column(nullable = false)
    @Builder.Default
    private Integer slowQueryUsage = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer lineageUsage = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer performanceHistoryUsage = 0;

    // Temporal metadata for future frequency/recency weighting
    @Column
    private LocalDateTime firstSeenAt;

    @Column
    private LocalDateTime lastSeenAt;

    @Column
    @Builder.Default
    private Integer distinctQueriesCount = 0;

    // Cardinality info (denormalized from ColumnProfile)
    @Column
    private Long distinctCount;

    @Column
    private Long totalRows;

    @Column(precision = 10, scale = 6)
    private BigDecimal selectivity;

    @Column(precision = 10, scale = 6)
    private BigDecimal cardinalityRatio;

    @Column(precision = 10, scale = 6)
    private BigDecimal nullRatio; // Percentage of NULL values (0.0 to 1.0)

    // Data skew analysis (BRAIN-design.md Section 3.2)
    @Column
    private Double skewCoefficient; // 0.0 (uniform) to 1.0 (highly skewed)

    @Column
    @Builder.Default
    private Boolean isHeavilySkewed = false; // True if skew > 0.7

    // Key classification (BRAIN-design.md Section 7)
    @Column(length = 50)
    @Builder.Default
    private String keyType = "NON_KEY"; // TRUE_KEY, ACCIDENTAL_KEY, SURROGATE_KEY, NON_KEY

    @Column(precision = 5, scale = 4)
    private BigDecimal keyConfidence; // 0.0 to 1.0 confidence score

    // Partitioning candidates (BRAIN-design.md Section 7)
    @Column
    @Builder.Default
    private Boolean isPartitionCandidate = false;

    @Column(length = 50)
    private String partitioningType; // RANGE, LIST, HASH

    @Column(precision = 5, scale = 2)
    private BigDecimal partitioningScore; // 0-100 score

    @Column(columnDefinition = "TEXT")
    private String partitioningRecommendation;

    // Enhanced scoring metrics
    @Column(precision = 10, scale = 2)
    private BigDecimal frequencyScore; // Uses per day

    @Column(precision = 10, scale = 2)
    private BigDecimal recencyScore; // Time-decay weighted

    @Column(precision = 10, scale = 2)
    private BigDecimal enhancedImportanceScore; // Combined with selectivity

    @Column(precision = 10, scale = 2)
    private BigDecimal mlPredictionScore; // ML-based prediction

    @Column
    private BigDecimal usesPerDay;

    // Index usage statistics
    @Column
    private String indexName;

    @Column
    private Long indexUsageCount;

    @Column
    private Long indexScanCount;

    @Column
    @Builder.Default
    private Boolean hasUnusedIndex = false;

    @Column
    private LocalDateTime lastUsedAt;

    // Anti-pattern flags
    @Column(nullable = false)
    @Builder.Default
    private Boolean hasAntiPatterns = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer antiPatternCount = 0;

    // Timestamps
    @Column(nullable = false)
    private LocalDateTime analyzedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (analyzedAt == null) {
            analyzedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
