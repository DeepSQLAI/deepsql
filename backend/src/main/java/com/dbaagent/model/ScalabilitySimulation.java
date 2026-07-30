package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Entity for storing scalability growth simulation results.
 * Implements BRAIN-design.md Section 10: "Scalability & Growth Reasoning"
 *
 * Predicts system performance under various growth scenarios:
 * - 2X: Double current data volume
 * - 5X: 5x current data volume
 * - 10X: 10x current data volume
 * - 20X: 20x current data volume
 */
@Entity
@Table(name = "scalability_simulation", indexes = {
    @Index(name = "idx_scalability_sim_connection", columnList = "connectionId"),
    @Index(name = "idx_scalability_sim_date", columnList = "connectionId,simulatedAt"),
    @Index(name = "idx_scalability_sim_risk", columnList = "connectionId,overallRiskLevel")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScalabilitySimulation {

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public enum GrowthScenario {
        GROWTH_2X("2X", 2.0),
        GROWTH_5X("5X", 5.0),
        GROWTH_10X("10X", 10.0),
        GROWTH_20X("20X", 20.0);

        private final String label;
        private final double multiplier;

        GrowthScenario(String label, double multiplier) {
            this.label = label;
            this.multiplier = multiplier;
        }

        public String getLabel() {
            return label;
        }

        public double getMultiplier() {
            return multiplier;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 255)
    private String connectionId;

    // Current state
    @Column
    private Long currentTotalRows;

    @Column
    private Long currentTotalSizeMb;

    @Column
    private Long currentAvgQueryTimeMs;

    // Growth scenario
    @Column(nullable = false, length = 20)
    private String growthScenario;  // 2X, 5X, 10X, 20X

    // Predicted state
    @Column
    private Long predictedTotalRows;

    @Column
    private Long predictedTotalSizeMb;

    @Column
    private Long predictedAvgQueryTimeMs;

    // Risk assessment
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RiskLevel overallRiskLevel;

    @Column(precision = 5, scale = 2)
    private BigDecimal scalabilityScore;  // 0-100 (100 = excellent scalability)

    // Specific risks identified
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> risksIdentified;  // Array of risk objects

    // Recommendations
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> recommendations;  // Array of recommendation objects

    // Simulation details
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> simulationAssumptions;  // Methodology and assumptions used

    @Column
    private Integer simulatedTables;  // Number of tables analyzed

    // Timestamps
    @Column
    private LocalDateTime simulatedAt;

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (simulatedAt == null) {
            simulatedAt = LocalDateTime.now();
        }
    }
}
