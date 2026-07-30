package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "growth_anomalies", indexes = {
    @Index(name = "idx_growth_anomaly_connection_table", columnList = "connection_id,table_name"),
    @Index(name = "idx_detection_time", columnList = "detection_timestamp"),
    @Index(name = "idx_acknowledged", columnList = "acknowledged"),
    @Index(name = "idx_severity", columnList = "severity"),
    @Index(name = "idx_snapshot", columnList = "snapshot_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrowthAnomaly {

    @Id
    private String id;

    @Column(name = "snapshot_id", nullable = false, length = 36)
    private String snapshotId;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "detection_timestamp", nullable = false)
    private LocalDateTime detectionTimestamp;

    @Column(name = "anomaly_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private AnomalyType anomalyType;

    @Column(name = "severity", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Severity severity;

    // Metrics at detection time
    @Column(name = "current_size_bytes")
    private Long currentSizeBytes;

    @Column(name = "previous_size_bytes")
    private Long previousSizeBytes;

    @Column(name = "size_growth_bytes")
    private Long sizeGrowthBytes;

    @Column(name = "size_growth_percent")
    private Double sizeGrowthPercent;

    @Column(name = "current_row_count")
    private Long currentRowCount;

    @Column(name = "previous_row_count")
    private Long previousRowCount;

    @Column(name = "row_count_growth")
    private Long rowCountGrowth;

    // Statistical data
    @Column(name = "z_score")
    private Double zScore;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "alert_id", length = 36)
    private String alertId; // Links to playbook_alerts

    // Acknowledgment tracking
    @Column
    private Boolean acknowledged = false;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    // Sentinel-DBA Analytics Fields
    @Column(name = "confidence_score")
    private Integer confidenceScore;  // 1-10 (Sentinel confidence in this detection)

    @Column(name = "attributed_event_id")
    private String attributedEventId;  // Links to database_events table

    @Column(name = "attributed_event_description")
    private String attributedEventDescription;  // Human-readable event description

    @Column(name = "growth_pattern")
    @Enumerated(EnumType.STRING)
    private GrowthPattern growthPattern;  // LINEAR, EXPONENTIAL, STEP_FUNCTION

    @Column(name = "forecast_id")
    private String forecastId;  // Links to capacity_forecasts table

    /**
     * Growth Pattern Types (for Sentinel analysis)
     */
    public enum GrowthPattern {
        LINEAR, EXPONENTIAL, STEP_FUNCTION, SEASONAL, STABLE, DECLINING, ERRATIC
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (detectionTimestamp == null) {
            detectionTimestamp = LocalDateTime.now();
        }
        if (acknowledged == null) {
            acknowledged = false;
        }
    }

    /**
     * Anomaly Type Enum
     */
    public enum AnomalyType {
        PERCENTAGE_GROWTH("Percentage Growth"),
        ABSOLUTE_GROWTH("Absolute Size Growth"),
        STATISTICAL_ANOMALY("Statistical Anomaly"),
        ROW_SPIKE("Row Count Spike"),
        NEW_TABLE("New Table Detected");

        private final String displayName;

        AnomalyType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Severity Enum - Must match PlaybookAlert.Severity for consistency
     */
    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * Acknowledge this anomaly
     * @param acknowledgedBy Username or identifier of who acknowledged
     */
    public void acknowledge(String acknowledgedBy) {
        this.acknowledged = true;
        this.acknowledgedBy = acknowledgedBy;
        this.acknowledgedAt = LocalDateTime.now();
    }

    /**
     * Generate human-readable description of the anomaly
     */
    public String getDescription() {
        if (description != null && !description.isEmpty()) {
            return description;
        }

        StringBuilder desc = new StringBuilder();
        desc.append(anomalyType.getDisplayName()).append(" detected for table ").append(tableName);

        switch (anomalyType) {
            case PERCENTAGE_GROWTH:
                desc.append(": Table size increased by ")
                    .append(String.format("%.1f%%", sizeGrowthPercent))
                    .append(" (from ").append(formatBytes(previousSizeBytes))
                    .append(" to ").append(formatBytes(currentSizeBytes))
                    .append(")");
                break;

            case ABSOLUTE_GROWTH:
                desc.append(": Table size increased by ")
                    .append(formatBytes(sizeGrowthBytes))
                    .append(" (from ").append(formatBytes(previousSizeBytes))
                    .append(" to ").append(formatBytes(currentSizeBytes))
                    .append(")");
                break;

            case STATISTICAL_ANOMALY:
                desc.append(": Growth rate is ")
                    .append(String.format("%.1f", Math.abs(zScore)))
                    .append(" standard deviations from the normal pattern")
                    .append(" (").append(String.format("%.1f%%", sizeGrowthPercent))
                    .append(" growth)");
                break;

            case ROW_SPIKE:
                desc.append(": Row count increased by ")
                    .append(String.format("%,d", rowCountGrowth))
                    .append(" rows (from ").append(String.format("%,d", previousRowCount))
                    .append(" to ").append(String.format("%,d", currentRowCount))
                    .append(")");
                break;

            case NEW_TABLE:
                desc.append(": First snapshot captured")
                    .append(" (size: ").append(formatBytes(currentSizeBytes))
                    .append(", rows: ").append(currentRowCount != null ? String.format("%,d", currentRowCount) : "N/A")
                    .append(")");
                break;
        }

        return desc.toString();
    }

    /**
     * Format bytes to human-readable string
     */
    private String formatBytes(Long bytes) {
        if (bytes == null) return "N/A";

        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Get severity color for UI display
     */
    public String getSeverityColor() {
        switch (severity) {
            case CRITICAL: return "#DC2626"; // Red
            case WARNING: return "#F59E0B";  // Orange
            case INFO: return "#3B82F6";     // Blue
            default: return "#6B7280";       // Gray
        }
    }
}
