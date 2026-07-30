package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Brain 2.0: Cost Calibration
 *
 * Learned adjustments to the database's cost model based on actual execution times.
 * Enables more accurate prediction of query performance.
 */
@Entity
@Table(name = "cost_calibration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostCalibration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String connectionId;

    /**
     * Overall cost multiplier to apply to database estimates.
     */
    @Column
    @Builder.Default
    private Double costMultiplier = 1.0;

    /**
     * Factor for sequential scan cost adjustment.
     */
    @Column
    @Builder.Default
    private Double seqScanCostFactor = 1.0;

    /**
     * Factor for index scan cost adjustment.
     */
    @Column
    @Builder.Default
    private Double indexScanCostFactor = 1.0;

    /**
     * Factor for join operation cost adjustment.
     */
    @Column
    @Builder.Default
    private Double joinCostFactor = 1.0;

    /**
     * Factor for sort operation cost adjustment.
     */
    @Column
    @Builder.Default
    private Double sortCostFactor = 1.0;

    /**
     * Number of training samples used.
     */
    @Column
    @Builder.Default
    private Integer trainingSamples = 0;

    /**
     * Last training error (RMSE).
     */
    @Column
    private Double lastTrainingError;

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
    }

    /**
     * Apply calibration to a cost estimate.
     */
    public double calibrate(double originalCost, String operationType) {
        double factor = switch (operationType.toUpperCase()) {
            case "SEQ SCAN", "SEQUENTIAL SCAN" -> seqScanCostFactor;
            case "INDEX SCAN", "INDEX ONLY SCAN" -> indexScanCostFactor;
            case "NESTED LOOP", "HASH JOIN", "MERGE JOIN" -> joinCostFactor;
            case "SORT" -> sortCostFactor;
            default -> 1.0;
        };

        return originalCost * costMultiplier * factor;
    }

    /**
     * Update calibration factors based on new observation.
     */
    public void updateFromObservation(double estimatedCost, double actualTime,
                                       String operationType) {
        if (estimatedCost <= 0 || actualTime <= 0) {
            return;
        }

        double observedRatio = actualTime / estimatedCost;
        double learningRate = 0.1; // Small steps to avoid overreaction

        // Update specific factor
        switch (operationType.toUpperCase()) {
            case "SEQ SCAN", "SEQUENTIAL SCAN" ->
                seqScanCostFactor = seqScanCostFactor * (1 - learningRate) +
                                    observedRatio * learningRate;
            case "INDEX SCAN", "INDEX ONLY SCAN" ->
                indexScanCostFactor = indexScanCostFactor * (1 - learningRate) +
                                      observedRatio * learningRate;
            case "NESTED LOOP", "HASH JOIN", "MERGE JOIN" ->
                joinCostFactor = joinCostFactor * (1 - learningRate) +
                                 observedRatio * learningRate;
            case "SORT" ->
                sortCostFactor = sortCostFactor * (1 - learningRate) +
                                 observedRatio * learningRate;
        }

        // Update overall multiplier as average
        costMultiplier = (seqScanCostFactor + indexScanCostFactor +
                         joinCostFactor + sortCostFactor) / 4.0;

        trainingSamples++;
    }

    /**
     * Check if calibration is reliable (has enough data).
     */
    public boolean isReliable() {
        return trainingSamples >= 50;
    }

    /**
     * Get confidence level based on training samples.
     */
    public double getConfidence() {
        if (trainingSamples < 10) return 0.1;
        if (trainingSamples < 50) return 0.5;
        if (trainingSamples < 200) return 0.8;
        return 0.95;
    }
}
