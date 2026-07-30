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
 * Represents a unified performance action/recommendation from various sources.
 * Actions are ranked by ROI (Return on Investment) = impact / effort.
 */
@Entity
@Table(name = "performance_action",
    indexes = {
        @Index(name = "idx_pa_connection_id", columnList = "connection_id"),
        @Index(name = "idx_pa_connection_status", columnList = "connection_id, status"),
        @Index(name = "idx_pa_connection_category", columnList = "connection_id, category"),
        @Index(name = "idx_pa_roi", columnList = "roi DESC"),
        @Index(name = "idx_pa_created_at", columnList = "created_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    /**
     * Category of the performance action.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private ActionCategory category;

    /**
     * Source that generated this recommendation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private ActionSource source;

    /**
     * Current status of the action.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ActionStatus status = ActionStatus.PENDING;

    /**
     * Short title of the action (e.g., "Missing Composite Index").
     */
    @Column(name = "title", nullable = false)
    private String title;

    /**
     * Detailed description of what needs to be done.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Target object (table name, query fingerprint, config parameter, etc.).
     */
    @Column(name = "target_object")
    private String targetObject;

    /**
     * Secondary target (column name, etc.).
     */
    @Column(name = "target_secondary")
    private String targetSecondary;

    /**
     * Impact score (0-100). Higher means more performance improvement potential.
     */
    @Column(name = "impact_score", nullable = false)
    private Integer impactScore;

    /**
     * Effort score (0-100). Higher means more work/risk to implement.
     */
    @Column(name = "effort_score", nullable = false)
    private Integer effortScore;

    /**
     * ROI = (impact / effort) * 100. Pre-calculated for efficient sorting.
     * Higher ROI means better value for the effort.
     */
    @Column(name = "roi", nullable = false)
    private Double roi;

    /**
     * SQL statement to execute (for INDEX, some QUERY actions).
     */
    @Column(name = "sql_statement", columnDefinition = "TEXT")
    private String sqlStatement;

    /**
     * Optimized query text (for QUERY_REWRITE actions).
     */
    @Column(name = "optimized_query", columnDefinition = "TEXT")
    private String optimizedQuery;

    /**
     * Configuration value to change (for CONFIG actions).
     */
    @Column(name = "config_value")
    private String configValue;

    /**
     * JSON metadata with additional context.
     * Can contain: metrics, affected queries count, estimated time savings, etc.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadataJson;

    /**
     * Reference ID to the source record (e.g., IndexRecommendation ID, SlowQuery ID).
     */
    @Column(name = "source_reference_id")
    private String sourceReferenceId;

    /**
     * Whether this action has a cached AI analysis available.
     */
    @Column(name = "has_cached_analysis")
    @Builder.Default
    private Boolean hasCachedAnalysis = false;

    /**
     * Estimated queries affected per day.
     */
    @Column(name = "queries_affected")
    private Long queriesAffected;

    /**
     * Estimated time savings in milliseconds per query.
     */
    @Column(name = "time_savings_ms")
    private Long timeSavingsMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * When the action was completed or dismissed.
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * User who resolved/dismissed the action.
     */
    @Column(name = "resolved_by")
    private String resolvedBy;

    /**
     * Notes added when resolving/dismissing.
     */
    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        calculateRoi();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        calculateRoi();
    }

    /**
     * Calculate ROI from impact and effort scores.
     */
    public void calculateRoi() {
        if (effortScore != null && effortScore > 0 && impactScore != null) {
            this.roi = (double) impactScore / effortScore * 100;
        } else if (impactScore != null) {
            this.roi = (double) impactScore * 100; // Zero effort = maximum ROI
        } else {
            this.roi = 0.0;
        }
    }

    /**
     * Action categories.
     */
    public enum ActionCategory {
        INDEX,          // Create/modify indexes
        QUERY_REWRITE,  // Rewrite slow queries
        CONFIG,         // Configuration tuning
        SCHEMA,         // Schema changes (anti-patterns, normalization)
        MAINTENANCE     // VACUUM, ANALYZE, REINDEX, etc.
    }

    /**
     * Sources that can generate performance actions.
     */
    public enum ActionSource {
        INDEX_ADVISOR,          // From IndexAdvisorService
        SLOW_QUERY_ANALYSIS,    // From QueryOptimizationService
        BRAIN_CONFIG_TUNING,    // From ConfigTuningService
        ANTI_PATTERN_DETECTION, // From AntiPatternDetectionService
        KEY_COLUMN_ANALYSIS,    // From KeyColumnAnalysisService
        BRAIN_INSIGHTS          // From BrainScoreService general recommendations
    }

    /**
     * Action status.
     */
    public enum ActionStatus {
        PENDING,      // Not yet addressed
        IN_PROGRESS,  // Being worked on
        COMPLETED,    // Successfully implemented
        DISMISSED     // Intentionally skipped
    }
}
