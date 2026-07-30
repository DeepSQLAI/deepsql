package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "semantic_join_model", indexes = {
    @Index(name = "idx_semantic_join_model_connection", columnList = "connectionId"),
    @Index(name = "idx_semantic_join_model_tables", columnList = "connectionId,sourceTable,targetTable"),
    @Index(name = "idx_semantic_join_model_preferred", columnList = "connectionId,preferred,confidenceScore")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_semantic_join_model",
        columnNames = {"connectionId", "sourceTable", "sourceColumn", "targetTable", "targetColumn"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticJoinModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String sourceTable;

    @Column(nullable = false)
    private String sourceColumn;

    @Column(nullable = false)
    private String targetTable;

    @Column(nullable = false)
    private String targetColumn;

    @Column(length = 50)
    private String relationshipType;

    @Column(nullable = false, length = 50)
    private String evidenceSource;

    @Column(columnDefinition = "TEXT")
    private String joinExpression;

    @Column(nullable = false)
    @Builder.Default
    private Boolean preferred = false;

    @Column(precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @Column(nullable = false)
    private LocalDateTime builtAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (builtAt == null) {
            builtAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
