package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity for storing database configuration tuning recommendations
 * Analyzes instance specs and current settings to recommend optimal configuration
 */
@Entity
@Table(name = "configuration_recommendations", indexes = {
    @Index(name = "idx_config_connection_id", columnList = "connectionId"),
    @Index(name = "idx_config_created_at", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurationRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String parameterName;

    @Column(nullable = false)
    private String currentValue;

    @Column(nullable = false)
    private String recommendedValue;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reasoning;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String impactDescription;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String applySql;  // SQL command to apply the change

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column
    private Double estimatedImprovementPercent;

    @Column
    private String category;  // Memory, CPU, I/O, Connections, WAL, etc.

    @Column
    private Boolean requiresRestart;  // Does this require database restart?

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime appliedAt;

    public enum Priority {
        CRITICAL,  // Severely suboptimal setting
        HIGH,      // Significant improvement possible
        MEDIUM,    // Moderate improvement
        LOW        // Minor optimization
    }

    public enum Status {
        PENDING,   // Not yet applied
        APPLIED,   // User marked as applied
        DISMISSED, // User dismissed recommendation
        OBSOLETE   // Superseded by newer recommendation
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Mark this recommendation as applied
     */
    public void markAsApplied() {
        this.status = Status.APPLIED;
        this.appliedAt = LocalDateTime.now();
    }

    /**
     * Mark this recommendation as dismissed
     */
    public void dismiss() {
        this.status = Status.DISMISSED;
    }
}
