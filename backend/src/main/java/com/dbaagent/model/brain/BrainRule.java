package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity for storing user-defined rules and domain knowledge hints.
 * Implements BRAIN-design.md Section 3.4: "User-Defined Rules & Hints"
 *
 * Example use cases:
 * - "This table is an immutable fact table"
 * - "This column is a soft foreign key"
 * - "Do not recommend indexes on this table"
 */
@Entity
@Table(name = "brain_rule", indexes = {
    @Index(name = "idx_brain_rule_connection", columnList = "connectionId"),
    @Index(name = "idx_brain_rule_table", columnList = "connectionId,tableName"),
    @Index(name = "idx_brain_rule_column", columnList = "connectionId,tableName,columnName"),
    @Index(name = "idx_brain_rule_type", columnList = "connectionId,ruleType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 255)
    private String connectionId;

    /**
     * Rule type determines how the rule is applied.
     *
     * Common types:
     * - EXCLUDE_FROM_ANALYSIS: Remove table/column from analysis
     * - SOFT_FOREIGN_KEY: Treat column as implicit foreign key
     * - HIGH_PRIORITY: Boost importance score
     * - LOW_PRIORITY: Reduce importance score
     * - IMMUTABLE_TABLE: Mark table as append-only/fact table
     * - NO_INDEX_RECOMMENDATION: Don't recommend indexes
     * - PARTITIONING_CANDIDATE: Suggest partitioning
     */
    @Column(nullable = false, length = 100)
    private String ruleType;

    @Column(length = 255)
    private String tableName;

    @Column(length = 255)
    private String columnName;

    /**
     * Human-readable explanation of the rule.
     * e.g., "This table stores historical data and should be partitioned by date"
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String ruleText;

    /**
     * Confidence level 0-100.
     * Higher confidence = more weight in scoring.
     */
    @Column
    @Builder.Default
    private Integer confidence = 50;

    /**
     * Whether the rule is currently active.
     */
    @Column
    @Builder.Default
    private Boolean isActive = true;

    @Column
    private LocalDateTime createdAt;

    @Column(length = 255)
    private String createdBy;

    @Column
    private LocalDateTime updatedAt;

    @Column(length = 255)
    private String updatedBy;

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
