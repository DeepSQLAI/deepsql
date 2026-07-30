package com.dbaagent.model;

import com.dbaagent.dto.WorkloadAnalysisData;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One holistic workload-analysis run for a connection.
 *
 * <p>Workload Analysis is the on-demand orchestrator that composes a single
 * report from engines that already exist plus the net-new pre-aggregation
 * analyzer. It does NOT re-rank indexes or re-run plan analysis itself — it
 * triggers a fresh {@code IndexRecommendationService} cycle, reads the
 * advisor's top-N, calls the rewrites-only optimizer for the most-affected
 * queries, runs the pre-aggregation detector, and folds in index-health
 * diagnostics. The composed sections live in the {@link #reportData} JSON
 * blob; the top-level columns are the queryable metadata (status, timing,
 * section counts) the list/poll endpoints need without deserializing the blob.
 */
@Entity
@Table(name = "workload_analysis_report", indexes = {
    @Index(name = "idx_workload_report_conn_started", columnList = "connectionId,startedAt"),
    @Index(name = "idx_workload_report_conn_status", columnList = "connectionId,status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadAnalysisReport {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** 0–100 — drives the UI progress bar while the long-running job runs. */
    @Column(nullable = false)
    @Builder.Default
    private Integer progress = 0;

    @Column(length = 120)
    private String currentStep;

    /** How many distinct queries entered the candidate set (most-affected ∪ regressed ∪ high-volume). */
    @Column(nullable = false)
    @Builder.Default
    private Integer candidateCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer indexRecCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer rewriteRecCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer preAggRecCount = 0;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime completedAt;

    @Column(length = 120)
    private String triggeredBy;

    @Column(columnDefinition = "TEXT")
    private String message;

    /** The composed report — index recs, rewrites, pre-agg recs, index health, summary. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_data", columnDefinition = "jsonb")
    private WorkloadAnalysisData reportData;
}
