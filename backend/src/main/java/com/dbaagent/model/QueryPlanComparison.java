package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Stores results of query plan comparisons.
 * Used to detect plan regressions and track changes over time.
 */
@Entity
@Table(name = "query_plan_comparison")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPlanComparison {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(name = "baseline_plan_id", length = 36)
    private String baselinePlanId;

    @Column(name = "current_plan_id", length = 36)
    private String currentPlanId;

    // Comparison results
    @Column(name = "plan_changed")
    private Boolean planChanged;

    @Column(name = "cost_changed")
    private Boolean costChanged;

    @Column(name = "cost_change_percent")
    private Double costChangePercent;

    @Column(name = "estimated_rows_change_percent")
    private Double estimatedRowsChangePercent;

    // Plan structure changes
    @Column(name = "added_operations", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] addedOperations;

    @Column(name = "removed_operations", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] removedOperations;

    @Column(name = "new_seq_scans", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] newSeqScans;

    @Column(name = "lost_index_scans", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] lostIndexScans;

    // Severity and status
    @Column(name = "severity", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(name = "is_regression")
    private Boolean isRegression;

    @Column(name = "is_acknowledged")
    private Boolean isAcknowledged;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    // Analysis
    @Column(name = "analysis_notes", columnDefinition = "TEXT")
    private String analysisNotes;

    @Column(name = "suggested_actions", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] suggestedActions;

    @Column(name = "compared_at", nullable = false)
    private LocalDateTime comparedAt;

    @PrePersist
    protected void onCreate() {
        if (comparedAt == null) {
            comparedAt = LocalDateTime.now();
        }
        if (isRegression == null) {
            isRegression = false;
        }
        if (isAcknowledged == null) {
            isAcknowledged = false;
        }
        if (severity == null) {
            severity = Severity.INFO;
        }
    }

    public enum Severity {
        INFO,      // Minor or no change
        WARNING,   // Noticeable change, review recommended
        CRITICAL   // Significant regression, immediate attention needed
    }

    /**
     * Get a human-readable summary of the comparison
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();

        if (Boolean.TRUE.equals(isRegression)) {
            sb.append("REGRESSION DETECTED: ");
        }

        if (Boolean.TRUE.equals(planChanged)) {
            sb.append("Plan structure changed. ");
        }

        if (costChangePercent != null && costChangePercent != 0) {
            if (costChangePercent > 0) {
                sb.append(String.format("Cost increased by %.1f%%. ", costChangePercent));
            } else {
                sb.append(String.format("Cost decreased by %.1f%%. ", Math.abs(costChangePercent)));
            }
        }

        if (newSeqScans != null && newSeqScans.length > 0) {
            sb.append(String.format("New sequential scans on: %s. ", String.join(", ", newSeqScans)));
        }

        if (lostIndexScans != null && lostIndexScans.length > 0) {
            sb.append(String.format("Lost index scans on: %s. ", String.join(", ", lostIndexScans)));
        }

        return sb.length() > 0 ? sb.toString().trim() : "No significant changes detected";
    }
}
