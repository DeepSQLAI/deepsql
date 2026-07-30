package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Capacity Forecast Entity
 * Stores Death Clock predictions and growth pattern analysis
 * Core of Sentinel-DBA predictive analytics
 */
@Entity
@Table(name = "capacity_forecasts", indexes = {
    @Index(name = "idx_forecast_connection", columnList = "connection_id"),
    @Index(name = "idx_forecast_table", columnList = "connection_id, table_name"),
    @Index(name = "idx_forecast_date", columnList = "forecast_date"),
    @Index(name = "idx_forecast_exhaustion", columnList = "storage_exhaustion_date"),
    @Index(name = "idx_forecast_risk", columnList = "risk_score, confidence_score")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapacityForecast {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "table_name")
    private String tableName;

    @Column(name = "forecast_date", nullable = false)
    private LocalDateTime forecastDate;

    // Death Clock predictions
    @Column(name = "storage_exhaustion_date")
    private LocalDateTime storageExhaustionDate;

    @Column(name = "iops_exhaustion_date")
    private LocalDateTime iopsExhaustionDate;

    @Column(name = "connection_exhaustion_date")
    private LocalDateTime connectionExhaustionDate;

    @Column(name = "cpu_exhaustion_date")
    private LocalDateTime cpuExhaustionDate;

    // Current state percentages
    @Column(name = "current_storage_percent")
    private Double currentStoragePercent;

    @Column(name = "current_iops_percent")
    private Double currentIopsPercent;

    @Column(name = "current_connection_percent")
    private Double currentConnectionPercent;

    @Column(name = "current_cpu_percent")
    private Double currentCpuPercent;

    // Growth rates (First Derivative - % per day)
    @Column(name = "daily_storage_growth_rate")
    private Double dailyStorageGrowthRate;

    @Column(name = "daily_iops_growth_rate")
    private Double dailyIopsGrowthRate;

    @Column(name = "daily_connection_growth_rate")
    private Double dailyConnectionGrowthRate;

    @Column(name = "daily_cpu_growth_rate")
    private Double dailyCpuGrowthRate;

    // Acceleration (Second Derivative - change in % per day)
    @Column(name = "storage_acceleration")
    private Double storageAcceleration;

    @Column(name = "iops_acceleration")
    private Double iopsAcceleration;

    @Column(name = "connection_acceleration")
    private Double connectionAcceleration;

    @Column(name = "cpu_acceleration")
    private Double cpuAcceleration;

    // Growth pattern detection
    @Column(name = "growth_pattern")
    @Enumerated(EnumType.STRING)
    private GrowthPattern growthPattern;

    @Column(name = "pattern_confidence")
    private Integer patternConfidence;

    // Attribution
    @Column(name = "attributed_event_id")
    private String attributedEventId;

    @Column(name = "attributed_event_description", columnDefinition = "TEXT")
    private String attributedEventDescription;

    // Risk assessment
    @Column(name = "risk_score")
    private Integer riskScore;  // 1-10 (10 = critical)

    @Column(name = "confidence_score")
    private Integer confidenceScore;  // 1-10 (10 = high confidence)

    @Column(name = "data_points_used")
    private Integer dataPointsUsed;

    // Metadata
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
        if (forecastDate == null) {
            forecastDate = LocalDateTime.now();
        }
    }

    /**
     * Growth Pattern Types
     */
    public enum GrowthPattern {
        LINEAR("Linear Growth"),
        EXPONENTIAL("Exponential Growth"),
        STEP_FUNCTION("Step Function"),
        SEASONAL("Seasonal Pattern"),
        STABLE("Stable/No Growth"),
        DECLINING("Declining"),
        ERRATIC("Erratic/Unpredictable");

        private final String displayName;

        GrowthPattern(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Calculate days until storage exhaustion
     */
    public Long getDaysToStorageExhaustion() {
        if (storageExhaustionDate == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (storageExhaustionDate.isBefore(now)) {
            return 0L;  // Already exhausted
        }
        return ChronoUnit.DAYS.between(now, storageExhaustionDate);
    }

    /**
     * Calculate days until IOPS exhaustion
     */
    public Long getDaysToIopsExhaustion() {
        if (iopsExhaustionDate == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (iopsExhaustionDate.isBefore(now)) {
            return 0L;
        }
        return ChronoUnit.DAYS.between(now, iopsExhaustionDate);
    }

    /**
     * Get the most critical (soonest) exhaustion date
     */
    public LocalDateTime getCriticalExhaustionDate() {
        LocalDateTime earliest = null;

        if (storageExhaustionDate != null && (earliest == null || storageExhaustionDate.isBefore(earliest))) {
            earliest = storageExhaustionDate;
        }
        if (iopsExhaustionDate != null && (earliest == null || iopsExhaustionDate.isBefore(earliest))) {
            earliest = iopsExhaustionDate;
        }
        if (connectionExhaustionDate != null && (earliest == null || connectionExhaustionDate.isBefore(earliest))) {
            earliest = connectionExhaustionDate;
        }
        if (cpuExhaustionDate != null && (earliest == null || cpuExhaustionDate.isBefore(earliest))) {
            earliest = cpuExhaustionDate;
        }

        return earliest;
    }

    /**
     * Get the resource type that will exhaust first
     */
    public String getCriticalResourceType() {
        LocalDateTime critical = getCriticalExhaustionDate();
        if (critical == null) {
            return "NONE";
        }

        if (critical.equals(storageExhaustionDate)) return "STORAGE";
        if (critical.equals(iopsExhaustionDate)) return "IOPS";
        if (critical.equals(connectionExhaustionDate)) return "CONNECTIONS";
        if (critical.equals(cpuExhaustionDate)) return "CPU";

        return "UNKNOWN";
    }

    /**
     * Check if storage is accelerating (second derivative positive)
     */
    public boolean isStorageAccelerating() {
        return storageAcceleration != null && storageAcceleration > 0.1;  // >0.1% increase in growth rate
    }

    /**
     * Get acceleration indicator for display (↑ ↓ →)
     */
    public String getStorageAccelerationIndicator() {
        if (storageAcceleration == null) return "";
        if (storageAcceleration > 0.1) return "↑";
        if (storageAcceleration < -0.1) return "↓";
        return "→";
    }

    /**
     * Get a human-readable summary of the forecast
     */
    public String getSummary() {
        StringBuilder summary = new StringBuilder();

        if (growthPattern != null) {
            summary.append(growthPattern.getDisplayName());
        }

        Long daysToExhaustion = getDaysToStorageExhaustion();
        if (daysToExhaustion != null) {
            summary.append(String.format(" - Storage exhaustion in %d days", daysToExhaustion));
        }

        if (storageAcceleration != null && Math.abs(storageAcceleration) > 0.1) {
            summary.append(String.format(" (acceleration: %+.2f%%/day²)", storageAcceleration));
        }

        return summary.toString();
    }

    /**
     * Calculate overall forecast health score (1-100)
     */
    public Integer getHealthScore() {
        if (riskScore == null) {
            return null;
        }
        // Invert risk score to get health (risk 10 = health 10, risk 1 = health 100)
        return 110 - (riskScore * 10);
    }
}
