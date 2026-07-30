package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Resource Limits Entity
 * Stores configured capacity limits for database connections
 * Used by Sentinel-DBA for Death Clock forecasting
 */
@Entity
@Table(name = "resource_limits", indexes = {
    @Index(name = "idx_resource_limits_connection", columnList = "connection_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceLimits {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    // Storage limits
    @Column(name = "max_storage_bytes")
    private Long maxStorageBytes;

    @Column(name = "current_storage_bytes")
    private Long currentStorageBytes;

    @Column(name = "storage_warning_percent")
    @Builder.Default
    private Double storageWarningPercent = 75.0;

    @Column(name = "storage_critical_percent")
    @Builder.Default
    private Double storageCriticalPercent = 90.0;

    // IOPS limits
    @Column(name = "max_iops")
    private Integer maxIops;

    @Column(name = "current_iops")
    private Integer currentIops;

    @Column(name = "iops_warning_percent")
    @Builder.Default
    private Double iopsWarningPercent = 75.0;

    @Column(name = "iops_critical_percent")
    @Builder.Default
    private Double iopsCriticalPercent = 90.0;

    // Connection limits
    @Column(name = "max_connections")
    private Integer maxConnections;

    @Column(name = "current_connections")
    private Integer currentConnections;

    @Column(name = "connection_warning_percent")
    @Builder.Default
    private Double connectionWarningPercent = 75.0;

    @Column(name = "connection_critical_percent")
    @Builder.Default
    private Double connectionCriticalPercent = 90.0;

    // CPU limits
    @Column(name = "max_cpu_percent")
    @Builder.Default
    private Integer maxCpuPercent = 100;

    @Column(name = "cpu_warning_percent")
    @Builder.Default
    private Double cpuWarningPercent = 75.0;

    @Column(name = "cpu_critical_percent")
    @Builder.Default
    private Double cpuCriticalPercent = 90.0;

    // Metadata
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_enabled")
    @Builder.Default
    private Boolean isEnabled = true;

    // Collection cadence: MINUTE, HOUR, DAY
    @Column(name = "collection_cadence")
    @Builder.Default
    private String collectionCadence = "HOUR";

    // Performance Insights retention in days
    @Column(name = "performance_insights_retention_days")
    @Builder.Default
    private Integer performanceInsightsRetentionDays = 7;

    // Slow-query analytics history retention in days. Replaces the old
    // "keep 20 entries per connection" cap with a configurable time window.
    @Column(name = "slow_query_history_retention_days")
    @Builder.Default
    private Integer slowQueryHistoryRetentionDays = 30;

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
     * Calculate current storage utilization percentage
     */
    public Double getCurrentStoragePercent() {
        if (maxStorageBytes == null || maxStorageBytes == 0 || currentStorageBytes == null) {
            return null;
        }
        return (currentStorageBytes.doubleValue() / maxStorageBytes) * 100.0;
    }

    /**
     * Calculate current IOPS utilization percentage
     */
    public Double getCurrentIopsPercent() {
        if (maxIops == null || maxIops == 0 || currentIops == null) {
            return null;
        }
        return (currentIops.doubleValue() / maxIops) * 100.0;
    }

    /**
     * Calculate current connection utilization percentage
     */
    public Double getCurrentConnectionPercent() {
        if (maxConnections == null || maxConnections == 0 || currentConnections == null) {
            return null;
        }
        return (currentConnections.doubleValue() / maxConnections) * 100.0;
    }

    /**
     * Get remaining storage capacity in bytes
     */
    public Long getRemainingStorageBytes() {
        if (maxStorageBytes == null || currentStorageBytes == null) {
            return null;
        }
        return maxStorageBytes - currentStorageBytes;
    }

    /**
     * Check if any resource is at warning level
     */
    public boolean hasWarnings() {
        Double storagePercent = getCurrentStoragePercent();
        Double iopsPercent = getCurrentIopsPercent();
        Double connectionPercent = getCurrentConnectionPercent();

        return (storagePercent != null && storagePercent >= storageWarningPercent) ||
               (iopsPercent != null && iopsPercent >= iopsWarningPercent) ||
               (connectionPercent != null && connectionPercent >= connectionWarningPercent);
    }

    /**
     * Check if any resource is at critical level
     */
    public boolean hasCritical() {
        Double storagePercent = getCurrentStoragePercent();
        Double iopsPercent = getCurrentIopsPercent();
        Double connectionPercent = getCurrentConnectionPercent();

        return (storagePercent != null && storagePercent >= storageCriticalPercent) ||
               (iopsPercent != null && iopsPercent >= iopsCriticalPercent) ||
               (connectionPercent != null && connectionPercent >= connectionCriticalPercent);
    }
}
