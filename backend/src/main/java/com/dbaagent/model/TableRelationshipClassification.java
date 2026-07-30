package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity for storing detailed classification of table relationships.
 *
 * Relationship Types:
 * - ONE_TO_ONE: Unique constraint on FK column
 * - ONE_TO_MANY: Standard FK relationship
 * - MANY_TO_MANY: Bridge/junction table pattern
 *
 * Relationship Strengths:
 * - STRONG: FK constraint exists
 * - INFERRED: Detected from naming conventions
 * - WEAK: Detected only from join patterns in queries
 */
@Entity
@Table(name = "table_relationship_classification", indexes = {
    @Index(name = "idx_rel_class_connection", columnList = "connectionId"),
    @Index(name = "idx_rel_class_schema", columnList = "schemaClassificationId"),
    @Index(name = "idx_rel_class_source", columnList = "connectionId,sourceTable"),
    @Index(name = "idx_rel_class_target", columnList = "connectionId,targetTable"),
    @Index(name = "idx_rel_class_strength", columnList = "connectionId,strength")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_rel_class", columnNames = {"schemaClassificationId", "sourceTable", "targetTable", "sourceColumn"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableRelationshipClassification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 255)
    private String connectionId;

    @Column(length = 255)
    private String schemaClassificationId;

    // ==================== Relationship Details ====================

    @Column(nullable = false, length = 255)
    private String sourceTable;

    @Column(nullable = false, length = 255)
    private String targetTable;

    @Column(length = 255)
    private String sourceColumn;

    @Column(length = 255)
    private String targetColumn;

    // ==================== Classification ====================

    /**
     * Type of relationship: ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY
     */
    @Column(length = 50)
    private String relationshipType;

    /**
     * Strength of relationship evidence:
     * - STRONG: FK constraint exists
     * - INFERRED: Detected from naming conventions
     * - WEAK: Detected only from join patterns
     */
    @Column(length = 50)
    private String strength;

    // ==================== Metrics ====================

    /**
     * How often this relationship is used in queries (from slow query log analysis).
     */
    @Column
    @Builder.Default
    private Integer joinFrequency = 0;

    /**
     * Percentage of FK values that exist in the parent table (referential integrity).
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal dataIntegrityPct;

    /**
     * Count of FK values that don't exist in the parent table (orphan records).
     */
    @Column
    @Builder.Default
    private Long orphanCount = 0L;

    // ==================== FK Details ====================

    /**
     * Whether the FK has ON DELETE CASCADE.
     */
    @Column
    @Builder.Default
    private Boolean isCascading = false;

    /**
     * Whether the FK column has an index.
     */
    @Column
    @Builder.Default
    private Boolean hasIndex = false;

    // ==================== Confidence and Validation ====================

    /**
     * Confidence in the relationship classification (0-100).
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    /**
     * Validation status: PENDING, VALIDATED, REJECTED
     */
    @Column(length = 20)
    @Builder.Default
    private String validationStatus = "PENDING";

    // ==================== Timestamps ====================

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
