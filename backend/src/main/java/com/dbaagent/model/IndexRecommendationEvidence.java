package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One contributing query for a parent {@link IndexRecommendationEntity}.
 *
 * The recurrence accumulator says "this candidate has appeared 7 times across
 * cycles," but the actual workload signal — the queries paying for the
 * missing index — lives here. Each refresh cycle upserts the top-K most
 * expensive contributing queries (by {@code total_exec_time_ms}) per
 * recommendation. Unique on {@code (recommendation_id, query_fingerprint)}
 * so the same query pattern doesn't insert multiple rows.
 *
 * Surfaced via {@code GET /index-recommendations/{id}/top} and the
 * {@code get_index_recommendations} MCP tool as the "why" payload.
 */
@Entity
@Table(name = "index_recommendation_evidence",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_rec_evidence",
        columnNames = {"recommendation_id", "query_fingerprint"}
    ),
    indexes = {
        @Index(name = "idx_rec_evidence_parent", columnList = "recommendation_id, total_exec_time_ms DESC")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexRecommendationEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Parent recommendation; cascade-deleted with the parent (V96). */
    @Column(name = "recommendation_id", nullable = false)
    private String recommendationId;

    /** Short SHA-256 (16 chars) of the normalised query — see {@link com.dbaagent.service.index.QueryEvidenceExtractor#fingerprint}. */
    @Column(name = "query_fingerprint", nullable = false, length = 64)
    private String queryFingerprint;

    /** A truncated example of the original SQL; helps a DBA recognise the pattern. */
    @Column(name = "example_sql", columnDefinition = "TEXT")
    private String exampleSql;

    @Column(nullable = false)
    @Builder.Default
    private Long calls = 0L;

    @Column(name = "mean_exec_time_ms", nullable = false)
    @Builder.Default
    private Double meanExecTimeMs = 0.0;

    /** {@code calls × mean_exec_time_ms} — the per-query workload contribution. */
    @Column(name = "total_exec_time_ms", nullable = false)
    @Builder.Default
    private Double totalExecTimeMs = 0.0;

    @Column(name = "rows_examined")
    private Long rowsExamined;

    /** Which {@link com.dbaagent.service.index.ColumnRole} the columns played in this query. */
    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (observedAt == null) observedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
