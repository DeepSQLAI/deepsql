package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Stores cached EXPLAIN plans for queries.
 * Enables plan comparison over time to detect regressions.
 */
@Entity
@Table(name = "query_plan_cache")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPlanCache {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "normalized_query", columnDefinition = "TEXT")
    private String normalizedQuery;

    @Column(name = "plan_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PlanType planType;

    @Column(name = "plan_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> planJson;

    @Column(name = "plan_hash", nullable = false, length = 64)
    private String planHash;

    @Column(name = "plan_summary", columnDefinition = "TEXT")
    private String planSummary;

    // Plan metrics
    @Column(name = "estimated_cost")
    private Double estimatedCost;

    @Column(name = "estimated_rows")
    private Long estimatedRows;

    @Column(name = "actual_time_ms")
    private Double actualTimeMs;

    @Column(name = "actual_rows")
    private Long actualRows;

    // Plan characteristics
    @Column(name = "uses_seq_scan")
    private Boolean usesSeqScan;

    @Column(name = "uses_index_scan")
    private Boolean usesIndexScan;

    @Column(name = "uses_bitmap_scan")
    private Boolean usesBitmapScan;

    @Column(name = "uses_nested_loop")
    private Boolean usesNestedLoop;

    @Column(name = "uses_hash_join")
    private Boolean usesHashJoin;

    @Column(name = "uses_merge_join")
    private Boolean usesMergeJoin;

    @Column(name = "uses_sort")
    private Boolean usesSort;

    @Column(name = "uses_materialize")
    private Boolean usesMaterialize;

    // Tables and indexes
    @Column(name = "tables_involved", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] tablesInvolved;

    @Column(name = "indexes_used", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] indexesUsed;

    @Column(name = "missing_indexes", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] missingIndexes;

    // Warnings and recommendations
    @Column(name = "warnings", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] warnings;

    @Column(name = "recommendations", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] recommendations;

    @Column(name = "is_baseline")
    private Boolean isBaseline;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
        }
        if (isBaseline == null) {
            isBaseline = false;
        }
    }

    public enum PlanType {
        EXPLAIN,
        EXPLAIN_ANALYZE
    }
}
