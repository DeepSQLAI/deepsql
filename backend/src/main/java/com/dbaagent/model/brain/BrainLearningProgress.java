package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Brain 2.0: Learning Progress Tracker
 *
 * Tracks overall Brain 2.0 learning progress for a connection.
 * Shows how much data has been collected and what capabilities are available.
 */
@Entity
@Table(name = "brain_learning_progress")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainLearningProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String connectionId;

    // Workload Intelligence progress
    @Column
    @Builder.Default
    private Integer workloadSnapshotsCollected = 0;

    @Column
    @Builder.Default
    private Boolean factorAnalysisCompleted = false;

    @Column
    @Builder.Default
    private Boolean workloadTypeIdentified = false;

    @Column
    @Builder.Default
    private Integer similarWorkloadsFound = 0;

    // Config Tuning progress
    @Column
    @Builder.Default
    private Boolean baselineObservationRecorded = false;

    @Column
    @Builder.Default
    private Boolean knobRankingCompleted = false;

    @Column
    @Builder.Default
    private Integer topKnobsIdentified = 0;

    @Column
    @Builder.Default
    private Boolean gpModelTrained = false;

    @Column
    @Builder.Default
    private Integer experimentsCompleted = 0;

    @Column
    @Builder.Default
    private Integer successfulExperiments = 0;

    // Query Intelligence progress
    @Column
    @Builder.Default
    private Integer columnStatsCollected = 0;

    @Column
    @Builder.Default
    private Integer planExecutionsRecorded = 0;

    @Column
    @Builder.Default
    private Integer patternsDiscovered = 0;

    @Column
    @Builder.Default
    private Integer cardinalityComparisons = 0;

    // Overall readiness
    @Column
    @Builder.Default
    private Boolean brainV2Ready = false;

    @Column
    @Builder.Default
    private Double readinessPercent = 0.0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        calculateReadiness();
    }

    /**
     * Calculate overall readiness percentage.
     */
    public void calculateReadiness() {
        double total = 0;
        double achieved = 0;

        // Workload Intelligence (25%)
        total += 25;
        if (workloadSnapshotsCollected >= 10) achieved += 5;
        if (workloadSnapshotsCollected >= 50) achieved += 5;
        if (factorAnalysisCompleted) achieved += 5;
        if (workloadTypeIdentified) achieved += 5;
        if (similarWorkloadsFound > 0) achieved += 5;

        // Config Tuning (25%)
        total += 25;
        if (baselineObservationRecorded) achieved += 5;
        if (knobRankingCompleted) achieved += 5;
        if (topKnobsIdentified >= 5) achieved += 5;
        if (gpModelTrained) achieved += 5;
        if (experimentsCompleted >= 3) achieved += 5;

        // Query Intelligence (25%)
        total += 25;
        if (columnStatsCollected >= 10) achieved += 5;
        if (columnStatsCollected >= 50) achieved += 5;
        if (planExecutionsRecorded >= 100) achieved += 5;
        if (patternsDiscovered >= 10) achieved += 5;
        if (cardinalityComparisons >= 50) achieved += 5;

        // Validation (25%)
        total += 25;
        double successRate = experimentsCompleted > 0 ?
            (double) successfulExperiments / experimentsCompleted : 0;
        if (successRate >= 0.5) achieved += 10;
        if (successRate >= 0.7) achieved += 10;
        if (experimentsCompleted >= 5 && successRate >= 0.6) achieved += 5;

        this.readinessPercent = (achieved / total) * 100;
        this.brainV2Ready = readinessPercent >= 60;
    }

    /**
     * Get stage description based on readiness.
     */
    public String getStageDescription() {
        if (readinessPercent < 20) {
            return "Initializing - Collecting baseline data";
        } else if (readinessPercent < 40) {
            return "Learning - Analyzing workload patterns";
        } else if (readinessPercent < 60) {
            return "Training - Building prediction models";
        } else if (readinessPercent < 80) {
            return "Validating - Testing recommendations";
        } else {
            return "Ready - Full Brain 2.0 capabilities available";
        }
    }
}
