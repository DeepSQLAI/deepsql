package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_optimization_candidate_run",
    uniqueConstraints = @UniqueConstraint(columnNames = {"connection_id", "query_fingerprint", "candidate_id"}),
    indexes = {
        @Index(name = "idx_qocr_conn_fingerprint", columnList = "connection_id, query_fingerprint"),
        @Index(name = "idx_qocr_candidate_status", columnList = "connection_id, status")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryOptimizationCandidateRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "query_fingerprint", nullable = false)
    private String queryFingerprint;

    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    @Column(name = "candidate_sql", columnDefinition = "TEXT")
    private String candidateSql;

    @Column(name = "plan_signature", length = 255)
    private String planSignature;

    @Column(name = "plan_text", columnDefinition = "TEXT")
    private String planText;

    @Column(name = "estimated_cost")
    private Double estimatedCost;

    @Column(name = "estimated_rows")
    private Double estimatedRows;

    @Column(name = "warnings", columnDefinition = "TEXT")
    private String warnings;

    @Column(name = "benchmark_ms")
    private Double benchmarkMs;

    @Column(name = "median_ms")
    private Double medianMs;

    @Column(name = "run_count")
    private Integer runCount;

    @Column(name = "row_count_result")
    private Long rowCountResult;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
        if (runCount == null) {
            runCount = 0;
        }
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
