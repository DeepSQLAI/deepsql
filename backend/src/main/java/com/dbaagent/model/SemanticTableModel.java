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
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "semantic_table_model", indexes = {
    @Index(name = "idx_semantic_table_model_connection", columnList = "connectionId"),
    @Index(name = "idx_semantic_table_model_role", columnList = "connectionId,tableRole"),
    @Index(name = "idx_semantic_table_model_domain", columnList = "connectionId,businessDomain")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_semantic_table_model", columnNames = {"connectionId", "tableName"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticTableModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String tableName;

    @Column(length = 50)
    private String tableRole;

    @Column(length = 50)
    private String businessDomain;

    @Column(columnDefinition = "TEXT")
    private String businessDescription;

    @Column(columnDefinition = "TEXT")
    private String grainDescription;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> keyColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> timeColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> temporalSemantics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> dimensionColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> filterColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> metricColumns;

    @Column(columnDefinition = "TEXT")
    private String businessTerms;

    @Column(precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @Column(columnDefinition = "TEXT")
    private String sourceSummary;

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
