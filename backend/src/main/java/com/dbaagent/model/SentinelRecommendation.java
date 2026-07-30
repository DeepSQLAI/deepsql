package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sentinel Recommendation Entity
 * AI-generated actionable recommendations based on Sentinel analysis
 * Provides specific remediation steps with impact assessment
 */
@Entity
@Table(name = "sentinel_recommendations", indexes = {
    @Index(name = "idx_recommendation_connection", columnList = "connection_id"),
    @Index(name = "idx_recommendation_status", columnList = "status"),
    @Index(name = "idx_recommendation_priority", columnList = "priority")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SentinelRecommendation {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "table_name")
    private String tableName;

    // Recommendation details
    @Column(name = "recommendation_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private RecommendationType recommendationType;

    @Column(name = "priority", nullable = false)
    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    // Impact assessment
    @Column(name = "estimated_storage_savings_bytes")
    private Long estimatedStorageSavingsBytes;

    @Column(name = "estimated_performance_improvement_percent")
    private Double estimatedPerformanceImprovementPercent;

    @Column(name = "estimated_downtime_hours")
    private Double estimatedDowntimeHours;

    // Forecast relationship
    @Column(name = "forecast_id")
    private String forecastId;

    @Column(name = "anomaly_id")
    private String anomalyId;

    // Execution tracking
    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "implemented_at")
    private LocalDateTime implementedAt;

    @Column(name = "implemented_by")
    private String implementedBy;

    @Column(name = "dismissal_reason", columnDefinition = "TEXT")
    private String dismissalReason;

    // Effectiveness tracking (post-implementation)
    @Column(name = "actual_storage_savings_bytes")
    private Long actualStorageSavingsBytes;

    @Column(name = "actual_performance_improvement_percent")
    private Double actualPerformanceImprovementPercent;

    // Metadata
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Recommendation Types
     */
    public enum RecommendationType {
        PARTITION("Implement Table Partitioning"),
        OPTIMIZE("Run OPTIMIZE/VACUUM"),
        SCALE_IOPS("Scale IOPS Capacity"),
        SCALE_STORAGE("Scale Storage Capacity"),
        PURGE_LOGS("Purge Old Logs"),
        DROP_INDEX("Drop Unused Index"),
        CREATE_INDEX("Create Missing Index"),
        ARCHIVE_DATA("Archive Old Data"),
        ADD_RETENTION_POLICY("Add Data Retention Policy"),
        TUNE_CONFIGURATION("Tune Database Configuration"),
        UPGRADE_INSTANCE("Upgrade Instance Type"),
        ENABLE_COMPRESSION("Enable Compression"),
        REORGANIZE_TABLE("Reorganize Table"),
        UPDATE_STATISTICS("Update Table Statistics"),
        REVIEW_QUERY("Review Slow Query"),
        IMPLEMENT_CACHING("Implement Query Caching"),
        MIGRATE_TO_PARTITION("Migrate to Partitioned Table"),
        CONSOLIDATE_INDEXES("Consolidate Redundant Indexes"),
        OTHER("Other Recommendation");

        private final String displayName;

        RecommendationType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Priority Levels (SLA-based)
     */
    public enum Priority {
        IMMEDIATE("Immediate - Act Now"),
        HOUR_24("Within 24 Hours"),
        HOUR_48("Within 48 Hours"),
        WEEK("Within 1 Week"),
        MONTH("Within 1 Month"),
        FUTURE("Future Planning");

        private final String displayName;

        Priority(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Integer getSlaHours() {
            return switch (this) {
                case IMMEDIATE -> 1;
                case HOUR_24 -> 24;
                case HOUR_48 -> 48;
                case WEEK -> 168;
                case MONTH -> 720;
                case FUTURE -> Integer.MAX_VALUE;
            };
        }
    }

    /**
     * Execution Status
     */
    public enum Status {
        PENDING("Pending Review"),
        IN_PROGRESS("In Progress"),
        COMPLETED("Completed"),
        DISMISSED("Dismissed");

        private final String displayName;

        Status(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Mark recommendation as implemented
     */
    public void markAsImplemented(String implementedBy) {
        this.status = Status.COMPLETED;
        this.implementedAt = LocalDateTime.now();
        this.implementedBy = implementedBy;
    }

    /**
     * Dismiss recommendation with reason
     */
    public void dismiss(String reason) {
        this.status = Status.DISMISSED;
        this.dismissalReason = reason;
    }

    /**
     * Calculate ROI score (return on investment)
     * Based on savings vs. downtime cost
     */
    public Double getRoiScore() {
        if (estimatedStorageSavingsBytes == null && estimatedPerformanceImprovementPercent == null) {
            return null;
        }

        double benefit = 0.0;

        if (estimatedStorageSavingsBytes != null) {
            // Assume $0.10 per GB per month
            benefit += (estimatedStorageSavingsBytes / (1024.0 * 1024 * 1024)) * 0.10 * 12;  // Annual savings
        }

        if (estimatedPerformanceImprovementPercent != null) {
            // Performance improvement value (arbitrary scoring)
            benefit += estimatedPerformanceImprovementPercent * 10;
        }

        double cost = estimatedDowntimeHours != null ? estimatedDowntimeHours * 100 : 0;  // $100/hour downtime cost

        if (cost == 0) {
            return benefit;  // No downtime cost
        }

        return (benefit - cost) / cost;  // ROI ratio
    }

    /**
     * Check if recommendation is overdue based on priority SLA
     */
    public boolean isOverdue() {
        if (status != Status.PENDING) {
            return false;  // Not pending, so can't be overdue
        }

        Integer slaHours = priority.getSlaHours();
        if (slaHours == Integer.MAX_VALUE) {
            return false;  // Future planning has no SLA
        }

        LocalDateTime deadline = createdAt.plusHours(slaHours);
        return LocalDateTime.now().isAfter(deadline);
    }

    /**
     * Get formatted storage savings
     */
    public String getFormattedEstimatedSavings() {
        if (estimatedStorageSavingsBytes == null) {
            return "N/A";
        }

        if (estimatedStorageSavingsBytes < 1024) {
            return estimatedStorageSavingsBytes + " B";
        } else if (estimatedStorageSavingsBytes < 1024 * 1024) {
            return String.format("%.2f KB", estimatedStorageSavingsBytes / 1024.0);
        } else if (estimatedStorageSavingsBytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", estimatedStorageSavingsBytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", estimatedStorageSavingsBytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * Calculate effectiveness ratio (actual vs. estimated)
     */
    public Double getEffectivenessRatio() {
        if (actualStorageSavingsBytes == null || estimatedStorageSavingsBytes == null || estimatedStorageSavingsBytes == 0) {
            return null;
        }

        return actualStorageSavingsBytes.doubleValue() / estimatedStorageSavingsBytes;
    }
}
