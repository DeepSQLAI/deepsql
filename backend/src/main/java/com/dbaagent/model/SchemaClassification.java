package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Entity for storing overall schema classification.
 * Implements BRAIN-design.md Section 6: "Schema Classification"
 *
 * Global Classifications:
 * - STAR: Central fact table(s) with dimension tables
 * - SNOWFLAKE: Normalized dimensions (depth > 1)
 * - HYBRID: Mix of star and snowflake patterns
 * - OLTP: Highly normalized, many-to-many relationships
 * - REPORTING: Denormalized, pre-aggregated tables
 * - ANTI_PATTERN: Cycles, unclear ownership
 */
@Entity
@Table(name = "schema_classification", indexes = {
    @Index(name = "idx_schema_class_connection", columnList = "connectionId"),
    @Index(name = "idx_schema_class_date", columnList = "connectionId,classifiedAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaClassification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 255)
    private String connectionId;

    /**
     * Global pattern classification.
     */
    @Column(nullable = false, length = 50)
    private String globalPattern;  // STAR, SNOWFLAKE, HYBRID, OLTP, REPORTING, ANTI_PATTERN

    /**
     * Confidence in classification (0-100).
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    // Metrics
    @Column
    private Integer totalTables;

    @Column
    private Integer factTables;

    @Column
    private Integer dimensionTables;

    @Column
    private Integer bridgeTables;

    @Column
    private Integer orphanedTables;

    // Pattern characteristics
    @Column(precision = 10, scale = 2)
    private BigDecimal avgFanOut;  // Average dimensions per fact

    @Column
    private Integer maxDimensionDepth;  // 1 = star, >1 = snowflake

    @Column(length = 20)
    private String normalizationLevel;  // 1NF, 2NF, 3NF, DENORMALIZED

    // Quality indicators
    @Column
    @Builder.Default
    private Boolean hasCycles = false;

    @Column
    @Builder.Default
    private Boolean unclearOwnership = false;

    @Column
    @Builder.Default
    private Integer missingForeignKeys = 0;

    /**
     * JSON breakdown explaining classification.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> classificationReasoning;

    // ==================== Aggregate Statistics (from enhanced classification) ====================

    @Column
    @Builder.Default
    private Integer tablesWithAntiPatterns = 0;

    @Column
    @Builder.Default
    private Integer criticalAntiPatterns = 0;

    @Column(precision = 5, scale = 2)
    private BigDecimal avgHealthScore;

    @Column
    @Builder.Default
    private Integer piiTablesCount = 0;

    @Column
    @Builder.Default
    private Integer partitionCandidatesCount = 0;

    // ==================== Timestamps ====================

    @Column
    private LocalDateTime classifiedAt;

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (classifiedAt == null) {
            classifiedAt = LocalDateTime.now();
        }
    }
}
