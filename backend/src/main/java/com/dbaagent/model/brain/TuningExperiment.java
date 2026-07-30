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
 * Brain 2.0: Tuning Experiment
 *
 * Tracks A/B testing of configuration changes to validate recommendations.
 * Records baseline metrics, applies changes, and measures outcomes.
 */
@Entity
@Table(name = "tuning_experiment", indexes = {
    @Index(name = "idx_tuning_exp_conn_status", columnList = "connectionId,status"),
    @Index(name = "idx_tuning_exp_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TuningExperiment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    /**
     * Link to the recommendation being tested.
     */
    @Column
    private String recommendationId;

    /**
     * Configuration changes being tested.
     * Format: {knob_name: {old: oldValue, new: newValue}}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Map<String, String>> knobChanges;

    // Baseline metrics
    @Column
    private Double baselineLatencyP50;

    @Column
    private Double baselineLatencyP99;

    @Column
    private Double baselineThroughput;

    @Column
    private Double baselineCacheHitRatio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> baselineMetrics;

    // New metrics after change
    @Column
    private Double newLatencyP50;

    @Column
    private Double newLatencyP99;

    @Column
    private Double newThroughput;

    @Column
    private Double newCacheHitRatio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> newMetrics;

    // Calculated improvements
    @Column
    private Double latencyImprovementPercent;

    @Column
    private Double throughputImprovementPercent;

    @Column
    private Double overallImprovementPercent;

    /**
     * Experiment status.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private ExperimentStatus status = ExperimentStatus.PENDING;

    /**
     * How long to observe before measuring.
     */
    @Column
    @Builder.Default
    private Integer observationPeriodMinutes = 30;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime completedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Start the experiment.
     */
    public void start() {
        this.status = ExperimentStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    /**
     * Complete the experiment with results.
     */
    public void complete() {
        this.status = ExperimentStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        calculateImprovements();
    }

    /**
     * Mark the experiment as failed.
     */
    public void fail(String error) {
        this.status = ExperimentStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = error;
    }

    /**
     * Roll back the experiment.
     */
    public void rollback() {
        this.status = ExperimentStatus.ROLLED_BACK;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Calculate improvement percentages.
     */
    private void calculateImprovements() {
        if (baselineLatencyP50 != null && newLatencyP50 != null && baselineLatencyP50 > 0) {
            latencyImprovementPercent = ((baselineLatencyP50 - newLatencyP50) / baselineLatencyP50) * 100;
        }
        if (baselineThroughput != null && newThroughput != null && baselineThroughput > 0) {
            throughputImprovementPercent = ((newThroughput - baselineThroughput) / baselineThroughput) * 100;
        }

        // Overall improvement is weighted average
        double latencyWeight = 0.6;
        double throughputWeight = 0.4;

        if (latencyImprovementPercent != null && throughputImprovementPercent != null) {
            overallImprovementPercent = (latencyImprovementPercent * latencyWeight) +
                                        (throughputImprovementPercent * throughputWeight);
        } else if (latencyImprovementPercent != null) {
            overallImprovementPercent = latencyImprovementPercent;
        } else if (throughputImprovementPercent != null) {
            overallImprovementPercent = throughputImprovementPercent;
        }
    }

    /**
     * Check if the experiment was successful (positive improvement).
     */
    public boolean isSuccessful() {
        return status == ExperimentStatus.COMPLETED &&
               overallImprovementPercent != null &&
               overallImprovementPercent > 0;
    }

    public enum ExperimentStatus {
        PENDING,        // Not yet started
        RUNNING,        // Currently observing
        COMPLETED,      // Finished with results
        FAILED,         // Error occurred
        ROLLED_BACK     // Changes reverted
    }
}
