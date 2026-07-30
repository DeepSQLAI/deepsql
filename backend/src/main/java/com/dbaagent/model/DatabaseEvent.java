package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Database Event Entity
 * Tracks deployments, schema changes, and maintenance events for correlation analysis
 * Used by Sentinel-DBA for attribution of growth anomalies
 */
@Entity
@Table(name = "database_events", indexes = {
    @Index(name = "idx_event_connection", columnList = "connection_id"),
    @Index(name = "idx_event_timestamp", columnList = "event_timestamp"),
    @Index(name = "idx_event_type", columnList = "event_type"),
    @Index(name = "idx_event_deployment_version", columnList = "deployment_version"),
    @Index(name = "idx_event_correlation", columnList = "connection_id, event_timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseEvent {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;

    @Column(name = "event_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private EventType eventType;

    // Event details
    @Column(name = "event_name")
    private String eventName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // Affected objects
    @Column(name = "affected_tables", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> affectedTables;

    @Column(name = "affected_indexes", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> affectedIndexes;

    // Event metadata
    @Column(name = "deployment_version")
    private String deploymentVersion;

    @Column(name = "deployment_tag")
    private String deploymentTag;

    @Column(name = "initiated_by")
    private String initiatedBy;

    // Impact tracking
    @Column(name = "pre_event_snapshot_id")
    private String preEventSnapshotId;

    @Column(name = "post_event_snapshot_id")
    private String postEventSnapshotId;

    @Column(name = "storage_delta_bytes")
    private Long storageDeltaBytes;

    @Column(name = "row_count_delta")
    private Long rowCountDelta;

    // Additional metadata (JSON for flexibility)
    @Column(name = "metadata", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> metadata;

    // Timestamps
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (eventTimestamp == null) {
            eventTimestamp = LocalDateTime.now();
        }
    }

    /**
     * Event Types for Classification
     */
    public enum EventType {
        DEPLOYMENT("Application Deployment"),
        SCHEMA_CHANGE("Schema Migration"),
        INDEX_CREATE("Index Creation"),
        INDEX_DROP("Index Deletion"),
        MAINTENANCE("Database Maintenance"),
        BACKUP("Backup Operation"),
        RESTORE("Restore Operation"),
        VACUUM("Vacuum/Optimize Operation"),
        PARTITION_ADD("Partition Addition"),
        PARTITION_DROP("Partition Deletion"),
        TABLE_CREATE("Table Creation"),
        TABLE_DROP("Table Deletion"),
        TABLE_ALTER("Table Alteration"),
        BULK_LOAD("Bulk Data Load"),
        BULK_DELETE("Bulk Data Deletion"),
        CONFIGURATION_CHANGE("Configuration Change"),
        SECURITY_UPDATE("Security Update"),
        UPGRADE("Database Version Upgrade"),
        MIGRATION("Data Migration"),
        OTHER("Other Event");

        private final String displayName;

        EventType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Calculate the impact severity based on storage delta
     */
    public String getImpactSeverity() {
        if (storageDeltaBytes == null) {
            return "UNKNOWN";
        }

        long absChange = Math.abs(storageDeltaBytes);

        if (absChange > 50L * 1024 * 1024 * 1024) {  // > 50GB
            return "CRITICAL";
        } else if (absChange > 10L * 1024 * 1024 * 1024) {  // > 10GB
            return "HIGH";
        } else if (absChange > 1L * 1024 * 1024 * 1024) {  // > 1GB
            return "MEDIUM";
        } else if (absChange > 100L * 1024 * 1024) {  // > 100MB
            return "LOW";
        } else {
            return "NEGLIGIBLE";
        }
    }

    /**
     * Check if this event is within correlation window of a given timestamp
     * (within +/- 24 hours)
     */
    public boolean isWithinCorrelationWindow(LocalDateTime timestamp) {
        if (eventTimestamp == null || timestamp == null) {
            return false;
        }

        LocalDateTime windowStart = timestamp.minusHours(24);
        LocalDateTime windowEnd = timestamp.plusHours(24);

        return eventTimestamp.isAfter(windowStart) && eventTimestamp.isBefore(windowEnd);
    }

    /**
     * Get a summary description of the event
     */
    public String getSummaryDescription() {
        StringBuilder summary = new StringBuilder();

        summary.append(eventType.getDisplayName());

        if (eventName != null && !eventName.isEmpty()) {
            summary.append(": ").append(eventName);
        }

        if (deploymentVersion != null && !deploymentVersion.isEmpty()) {
            summary.append(" (v").append(deploymentVersion).append(")");
        }

        if (affectedTables != null && !affectedTables.isEmpty()) {
            summary.append(" - Tables: ").append(String.join(", ", affectedTables));
        }

        return summary.toString();
    }

    /**
     * Format storage delta for display
     */
    public String getFormattedStorageDelta() {
        if (storageDeltaBytes == null) {
            return "N/A";
        }

        long absBytes = Math.abs(storageDeltaBytes);
        String sign = storageDeltaBytes >= 0 ? "+" : "-";

        if (absBytes < 1024) {
            return sign + absBytes + " B";
        } else if (absBytes < 1024 * 1024) {
            return String.format("%s%.2f KB", sign, absBytes / 1024.0);
        } else if (absBytes < 1024 * 1024 * 1024) {
            return String.format("%s%.2f MB", sign, absBytes / (1024.0 * 1024));
        } else {
            return String.format("%s%.2f GB", sign, absBytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * Check if this is a high-risk event type
     */
    public boolean isHighRiskEvent() {
        return eventType == EventType.SCHEMA_CHANGE ||
               eventType == EventType.BULK_LOAD ||
               eventType == EventType.DEPLOYMENT ||
               eventType == EventType.MIGRATION;
    }
}
