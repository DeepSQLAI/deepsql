package com.dbaagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_lineage", indexes = {
    @Index(name = "idx_query_lineage_connection", columnList = "connectionId"),
    @Index(name = "idx_query_lineage_created", columnList = "createdAt"),
    @Index(name = "idx_query_lineage_source", columnList = "source"),
    @Index(name = "idx_query_lineage_query_hash", columnList = "queryHash"),
    @Index(name = "idx_query_lineage_analysis", columnList = "analysisId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryLineage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String queryText;

    @Column
    private String queryHash;

    @Column(columnDefinition = "TEXT")
    private String normalizedQuery;

    @Column(columnDefinition = "TEXT")
    private String tablesUsed;

    @Column(columnDefinition = "TEXT")
    private String columnsUsed;

    // Slow query log metadata (optional)
    @Column
    private Double avgExecutionTimeMs;

    @Column
    private Double maxExecutionTimeMs;

    @Column
    private Double minExecutionTimeMs;

    @Column
    private Double totalExecutionTimeMs;

    @Column
    private Long callCount;

    @Column
    private Long rowsExamined;

    @Column
    private Long rowsSent;

    @Column(length = 32)
    private String severity;

    @Column
    private Double performanceImpact;

    @Column
    private LocalDateTime firstSeenAt;

    @Column
    private LocalDateTime lastSeenAt;

    @Column
    private String analysisId;

    @Column
    private String analysisTimeRange;

    @Column(columnDefinition = "TEXT")
    private String sourceDetails;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
