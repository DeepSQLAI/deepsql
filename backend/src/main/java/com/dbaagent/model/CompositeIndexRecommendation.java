package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores recommendations for composite (multi-column) indexes
 * based on column co-occurrence patterns in queries.
 */
@Entity
@Table(name = "composite_index_recommendation", indexes = {
    @Index(name = "idx_composite_index_rec_connection", columnList = "connectionId"),
    @Index(name = "idx_composite_index_rec_table", columnList = "tableName"),
    @Index(name = "idx_composite_index_rec_priority", columnList = "priority,status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeIndexRecommendation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String tableName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String columnNames; // JSON array of column names

    @Column(length = 500)
    private String recommendationReason;

    @Column(nullable = false)
    private Integer coOccurrenceCount;

    @Column(precision = 10, scale = 6)
    private BigDecimal selectivityProduct;

    @Column(precision = 10, scale = 2)
    private BigDecimal estimatedBenefitScore;

    @Column(columnDefinition = "TEXT")
    private String suggestedIndexSql;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    @Builder.Default
    private Status status = Status.PENDING;

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

    public enum Priority {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum Status {
        PENDING, ACKNOWLEDGED, IMPLEMENTED, REJECTED
    }
}
