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

@Entity
@Table(name = "growth_alert_configurations",
       uniqueConstraints = @UniqueConstraint(name = "unique_connection_table", columnNames = {"connection_id", "table_name"}),
       indexes = {
           @Index(name = "idx_connection", columnList = "connection_id"),
           @Index(name = "idx_enabled", columnList = "is_enabled")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrowthAlertConfiguration {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "table_name")
    private String tableName; // NULL = global config for connection

    // Percentage growth thresholds (per hour)
    @Column(name = "percentage_growth_warning")
    @Builder.Default
    private Double percentageGrowthWarning = 50.0;

    @Column(name = "percentage_growth_critical")
    @Builder.Default
    private Double percentageGrowthCritical = 100.0;

    // Absolute size growth thresholds (bytes per hour)
    @Column(name = "absolute_growth_warning_bytes")
    @Builder.Default
    private Long absoluteGrowthWarningBytes = 10737418240L; // 10GB

    @Column(name = "absolute_growth_critical_bytes")
    @Builder.Default
    private Long absoluteGrowthCriticalBytes = 53687091200L; // 50GB

    // Row count spike thresholds (rows per hour)
    @Column(name = "row_spike_warning")
    @Builder.Default
    private Long rowSpikeWarning = 1000000L; // 1M rows

    @Column(name = "row_spike_critical")
    @Builder.Default
    private Long rowSpikeCritical = 10000000L; // 10M rows

    @Column(name = "row_spike_percentage")
    @Builder.Default
    private Double rowSpikePercentage = 200.0; // 200% increase

    // Statistical detection settings
    @Column(name = "z_score_threshold")
    @Builder.Default
    private Double zScoreThreshold = 3.0; // 3 standard deviations

    @Column(name = "historical_window_hours")
    @Builder.Default
    private Integer historicalWindowHours = 168; // 7 days

    // Notification settings - JSON columns
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_channels", columnDefinition = "JSON")
    private List<String> notificationChannels; // ["in-app", "email", "webhook"]

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "email_recipients", columnDefinition = "JSON")
    private List<String> emailRecipients; // ["admin@example.com"]

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    // Alert throttling
    @Column(name = "min_hours_between_alerts")
    @Builder.Default
    private Integer minHoursBetweenAlerts = 1;

    @Column(name = "last_alert_sent")
    private LocalDateTime lastAlertSent;

    @Column(name = "is_enabled")
    @Builder.Default
    private Boolean isEnabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
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
     * Check if an alert can be sent based on throttling settings
     * @return true if enough time has passed since last alert
     */
    public boolean canSendAlert() {
        if (!isEnabled) {
            return false;
        }

        if (lastAlertSent == null) {
            return true;
        }

        LocalDateTime nextAllowedAlert = lastAlertSent.plusHours(minHoursBetweenAlerts);
        return LocalDateTime.now().isAfter(nextAllowedAlert);
    }

    /**
     * Mark that an alert was sent
     */
    public void markAlertSent() {
        this.lastAlertSent = LocalDateTime.now();
    }

    /**
     * Check if this is a global configuration (no specific table)
     */
    public boolean isGlobal() {
        return tableName == null || tableName.isEmpty();
    }

    /**
     * Get configuration display name
     */
    public String getConfigName() {
        return isGlobal() ? "Global Configuration" : "Configuration for " + tableName;
    }
}
