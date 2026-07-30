package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Brain 2.0: Configuration Observation
 *
 * Records configuration experiments and their performance outcomes.
 * Used to train the Gaussian Process model for configuration recommendations.
 *
 * Inspired by OtterTune's observation collection mechanism.
 */
@Entity
@Table(name = "configuration_observation", indexes = {
    @Index(name = "idx_config_obs_conn_time", columnList = "connectionId,observedAt"),
    @Index(name = "idx_config_obs_fingerprint", columnList = "workloadFingerprint")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurationObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    /**
     * Configuration snapshot - all knob values at time of observation.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, String> configuration;

    /**
     * Performance metrics before configuration (if applicable).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> metricsBefore;

    /**
     * Performance metrics after observation period.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Double> metricsAfter;

    /**
     * Key performance indicators.
     */
    @Column
    private Double latencyP50Before;

    @Column
    private Double latencyP50After;

    @Column
    private Double latencyP99Before;

    @Column
    private Double latencyP99After;

    @Column
    private Double throughputBefore;

    @Column
    private Double throughputAfter;

    /**
     * Calculated improvement percentage.
     */
    @Column
    private Double improvementPercent;

    /**
     * Type of observation.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ObservationType observationType;

    /**
     * Duration of the observation period in minutes.
     */
    @Column
    private Integer observationDurationMinutes;

    /**
     * Workload fingerprint at time of observation for context.
     */
    @Column(length = 64)
    private String workloadFingerprint;

    @Column(nullable = false)
    private LocalDateTime observedAt;

    @PrePersist
    protected void onCreate() {
        if (observedAt == null) {
            observedAt = LocalDateTime.now();
        }
    }

    /**
     * Calculate improvement percent based on latency reduction.
     */
    public void calculateImprovement() {
        if (latencyP50Before != null && latencyP50After != null && latencyP50Before > 0) {
            this.improvementPercent = ((latencyP50Before - latencyP50After) / latencyP50Before) * 100;
        } else if (throughputBefore != null && throughputAfter != null && throughputBefore > 0) {
            this.improvementPercent = ((throughputAfter - throughputBefore) / throughputBefore) * 100;
        }
    }

    public enum ObservationType {
        BASELINE,       // Initial observation before any tuning
        RECOMMENDED,    // Configuration recommended by the tuner
        MANUAL,         // Manual configuration change by user
        EXPERIMENT,     // A/B testing experiment
        ROLLBACK        // Rolled back to previous configuration
    }
}
