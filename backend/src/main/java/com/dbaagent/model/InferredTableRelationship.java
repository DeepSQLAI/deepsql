package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity for storing table relationships inferred from JOIN patterns
 * in slow query logs and query lineage.
 *
 * These inferred relationships help identify table connections even when
 * the database lacks explicit foreign key constraints.
 */
@Entity
@Table(name = "inferred_table_relationship", indexes = {
    @Index(name = "idx_inferred_rel_connection", columnList = "connectionId"),
    @Index(name = "idx_inferred_rel_source", columnList = "connectionId,sourceTable"),
    @Index(name = "idx_inferred_rel_target", columnList = "connectionId,targetTable"),
    @Index(name = "idx_inferred_rel_confidence", columnList = "connectionId,confidenceScore"),
    @Index(name = "idx_inferred_rel_status", columnList = "connectionId,status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InferredTableRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 255)
    private String connectionId;

    /**
     * Source table (FK side of the relationship).
     */
    @Column(nullable = false, length = 255)
    private String sourceTable;

    /**
     * Source column (typically ends with _id, _key, _fk).
     */
    @Column(nullable = false, length = 255)
    private String sourceColumn;

    /**
     * Target table (PK side of the relationship).
     */
    @Column(nullable = false, length = 255)
    private String targetTable;

    /**
     * Target column (typically 'id' or primary key).
     */
    @Column(nullable = false, length = 255)
    private String targetColumn;

    /**
     * Number of times this JOIN pattern was observed.
     */
    @Column
    @Builder.Default
    private Integer joinCount = 1;

    /**
     * Number of distinct queries using this JOIN pattern.
     */
    @Column
    @Builder.Default
    private Integer distinctQueryCount = 1;

    /**
     * Confidence score (0-100) based on:
     * - Join frequency (joinCount * 5, max 50)
     * - Query diversity (distinctQueryCount * 10, max 30)
     * - Consistency bonus (20 if >= 3 distinct queries)
     */
    @Column(precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal confidenceScore = BigDecimal.ZERO;

    /**
     * Whether this is a self-join (source_table = target_table).
     */
    @Column
    @Builder.Default
    private Boolean isSelfJoin = false;

    /**
     * Cardinality of the relationship: N:1, 1:N, N:M, 1:1.
     */
    @Column(length = 20)
    private String cardinality;

    /**
     * Status for user validation:
     * - INFERRED: Auto-detected from query patterns
     * - VALIDATED: User confirmed the relationship
     * - REJECTED: User rejected the relationship
     */
    @Column(length = 20)
    @Builder.Default
    private String status = "INFERRED";

    /**
     * Sample query that demonstrates this JOIN pattern.
     */
    @Column(columnDefinition = "TEXT")
    private String sampleQuery;

    /**
     * When this relationship was first detected.
     */
    @Column
    private LocalDateTime firstSeenAt;

    /**
     * When this relationship was last seen in a query.
     */
    @Column
    private LocalDateTime lastSeenAt;

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
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
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Relationship status enum.
     */
    public enum Status {
        INFERRED,
        VALIDATED,
        REJECTED
    }
}
