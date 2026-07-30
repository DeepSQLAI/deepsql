package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity for storing per-table growth predictions.
 * Implements BRAIN-design.md Section 10: "Scalability & Growth Reasoning"
 *
 * Stores individual table forecasts under growth scenarios.
 */
@Entity
@Table(name = "table_growth_prediction", indexes = {
    @Index(name = "idx_table_growth_sim", columnList = "scalabilitySimulationId"),
    @Index(name = "idx_table_growth_connection", columnList = "connectionId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableGrowthPrediction {

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String scalabilitySimulationId;

    @Column(nullable = false, length = 255)
    private String connectionId;

    @Column(nullable = false, length = 255)
    private String tableName;

    // Current metrics
    @Column
    private Long currentRows;

    @Column
    private Long currentSizeMb;

    @Column
    private Integer currentIndexCount;

    // Predicted at growth scenario
    @Column
    private Long predictedRows;

    @Column
    private Long predictedSizeMb;

    @Column
    private Long predictedFullScanTimeMs;

    // Risk factors
    @Column
    @Builder.Default
    private Boolean lacksPartitioning = false;

    @Column
    @Builder.Default
    private Boolean missingCriticalIndexes = false;

    @Column
    @Builder.Default
    private Integer inefficientQueries = 0;  // Count of queries with high examination ratio

    // Recommendations
    @Column(columnDefinition = "TEXT")
    private String recommendedActions;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Priority priority;

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
