package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "dashboard_alerts", indexes = {
    @Index(name = "idx_dashboard_alerts_dashboard_id", columnList = "dashboardId"),
    @Index(name = "idx_dashboard_alerts_due", columnList = "isEnabled, lastCheckedAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID dashboardId;

    @Column(nullable = false)
    private String connectionId;

    // The alert runs as whoever created it (their agent profile/session) — there is
    // no ambient "system" identity to fall back to for a background job, and running
    // it as an arbitrary admin would let anyone's alert read data through someone
    // else's access grant.
    @Column(nullable = false)
    private String createdByUsername;

    // Natural-language threshold, e.g. "alert if error_rate exceeds 5% in the last hour".
    @Column(nullable = false, columnDefinition = "TEXT")
    private String conditionText;

    // Comma-separated subset of: in-app, email, webhook.
    @Column(nullable = false, length = 255)
    private String channels = "in-app";

    @Column(length = 1000)
    private String emailRecipients;

    @Column(length = 1000)
    private String webhookUrl;

    @Column(nullable = false)
    private Boolean isEnabled = true;

    @Column(nullable = false)
    private Integer checkIntervalMinutes = 15;

    // Minimum time between two firings, independent of check interval — a condition
    // that stays true for hours should page once, not every 15 minutes.
    @Column(nullable = false)
    private Integer cooldownMinutes = 60;

    @Column
    private LocalDateTime lastCheckedAt;

    @Column
    private LocalDateTime lastFiredAt;

    @Column(length = 16)
    private String lastVerdict;

    @Column(columnDefinition = "TEXT")
    private String lastReason;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void applyDefaults() {
        if (isEnabled == null) isEnabled = true;
        if (checkIntervalMinutes == null) checkIntervalMinutes = 15;
        if (cooldownMinutes == null) cooldownMinutes = 60;
        if (channels == null || channels.isBlank()) channels = "in-app";
    }
}
