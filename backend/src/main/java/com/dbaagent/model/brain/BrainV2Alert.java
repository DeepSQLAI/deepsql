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
 * Brain 2.0: Alert
 *
 * Alerts specific to Brain 2.0 features like workload changes,
 * configuration drift, and cardinality degradation.
 */
@Entity
@Table(name = "brain_v2_alert", indexes = {
    @Index(name = "idx_v2_alert_conn_status", columnList = "connectionId,status,createdAt"),
    @Index(name = "idx_v2_alert_type", columnList = "alertType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainV2Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    /**
     * Type of alert.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AlertType alertType;

    /**
     * Severity level.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    /**
     * Alert title.
     */
    @Column(nullable = false)
    private String title;

    /**
     * Alert message with details.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    /**
     * Additional context data.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> contextData;

    /**
     * Recommended action to take.
     */
    @Column(columnDefinition = "TEXT")
    private String recommendedAction;

    /**
     * Alert status.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column
    private LocalDateTime acknowledgedAt;

    @Column
    private LocalDateTime resolvedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Acknowledge the alert.
     */
    public void acknowledge() {
        this.status = Status.ACKNOWLEDGED;
        this.acknowledgedAt = LocalDateTime.now();
    }

    /**
     * Resolve the alert.
     */
    public void resolve() {
        this.status = Status.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
    }

    public enum AlertType {
        WORKLOAD_CHANGE,            // Significant workload pattern change
        CONFIG_DRIFT,               // Configuration drifted from optimal
        CARDINALITY_DEGRADATION,    // Cardinality estimates getting worse
        EXPERIMENT_FAILED,          // Tuning experiment failed
        EXPERIMENT_SUCCESS,         // Tuning experiment succeeded (info)
        MODEL_RETRAIN_NEEDED,       // GP model needs retraining
        PLAN_REGRESSION,            // Query plan regressed
        SIMILAR_WORKLOAD_FOUND,     // Found similar workload to learn from
        LEARNING_MILESTONE          // Reached a learning milestone
    }

    public enum Severity {
        INFO,
        WARNING,
        HIGH,
        CRITICAL
    }

    public enum Status {
        ACTIVE,
        ACKNOWLEDGED,
        RESOLVED
    }

    /**
     * Factory method for workload change alert.
     */
    public static BrainV2Alert workloadChange(String connectionId, String oldType, String newType,
                                               double confidence) {
        return BrainV2Alert.builder()
            .connectionId(connectionId)
            .alertType(AlertType.WORKLOAD_CHANGE)
            .severity(Severity.WARNING)
            .title("Workload Pattern Changed")
            .message(String.format(
                "Detected workload change from %s to %s (confidence: %.1f%%). " +
                "Configuration recommendations may need to be updated.",
                oldType, newType, confidence
            ))
            .contextData(Map.of(
                "oldWorkloadType", oldType,
                "newWorkloadType", newType,
                "confidence", confidence
            ))
            .recommendedAction("Review current configuration and consider running new tuning experiments")
            .build();
    }

    /**
     * Factory method for experiment success alert.
     */
    public static BrainV2Alert experimentSuccess(String connectionId, double improvementPercent) {
        return BrainV2Alert.builder()
            .connectionId(connectionId)
            .alertType(AlertType.EXPERIMENT_SUCCESS)
            .severity(Severity.INFO)
            .title("Tuning Experiment Successful")
            .message(String.format(
                "Configuration change achieved %.1f%% performance improvement.",
                improvementPercent
            ))
            .contextData(Map.of("improvementPercent", improvementPercent))
            .build();
    }

    /**
     * Factory method for workload change alert (simplified).
     */
    public static BrainV2Alert workloadChange(String connectionId, String oldType, String newType) {
        return BrainV2Alert.builder()
            .connectionId(connectionId)
            .alertType(AlertType.WORKLOAD_CHANGE)
            .severity(Severity.WARNING)
            .title("Workload Pattern Changed")
            .message(String.format(
                "Detected workload change from %s to %s. " +
                "Configuration recommendations may need to be updated.",
                oldType, newType
            ))
            .contextData(Map.of(
                "oldWorkloadType", oldType,
                "newWorkloadType", newType
            ))
            .recommendedAction("Review current configuration and consider running new tuning experiments")
            .build();
    }

    /**
     * Factory method for cardinality degradation alert.
     */
    public static BrainV2Alert cardinalityDegradation(String connectionId, double errorRatio, String details) {
        Severity severity = errorRatio > 100 ? Severity.HIGH : Severity.WARNING;

        return BrainV2Alert.builder()
            .connectionId(connectionId)
            .alertType(AlertType.CARDINALITY_DEGRADATION)
            .severity(severity)
            .title("Cardinality Estimation Degradation")
            .message(String.format(
                "Cardinality estimation accuracy has degraded. Average error ratio: %.2f. %s",
                errorRatio, details
            ))
            .contextData(Map.of(
                "errorRatio", errorRatio,
                "details", details
            ))
            .recommendedAction("Consider refreshing column statistics or reviewing recent schema changes")
            .build();
    }

    /**
     * Factory method for experiment failed alert.
     */
    public static BrainV2Alert experimentFailed(String connectionId, String experimentId, String reason) {
        return BrainV2Alert.builder()
            .connectionId(connectionId)
            .alertType(AlertType.EXPERIMENT_FAILED)
            .severity(Severity.WARNING)
            .title("Tuning Experiment Failed")
            .message(String.format(
                "Configuration experiment %s did not improve performance. Reason: %s",
                experimentId, reason
            ))
            .contextData(Map.of(
                "experimentId", experimentId,
                "failureReason", reason
            ))
            .recommendedAction("Review experiment parameters and consider alternative configurations")
            .build();
    }

    /**
     * Factory method for learning milestone alert.
     */
    public static BrainV2Alert learningMilestone(String connectionId, String milestone, double readinessPercent) {
        return BrainV2Alert.builder()
            .connectionId(connectionId)
            .alertType(AlertType.LEARNING_MILESTONE)
            .severity(Severity.INFO)
            .title("Learning Milestone Reached")
            .message(String.format(
                "Brain 2.0 has reached a new milestone: %s. Current readiness: %.1f%%",
                milestone, readinessPercent
            ))
            .contextData(Map.of(
                "milestone", milestone,
                "readinessPercent", readinessPercent
            ))
            .build();
    }

    /**
     * Factory method for similar workload found alert.
     */
    public static BrainV2Alert similarWorkloadFound(String connectionId, String similarConnectionId,
                                                     double similarityScore) {
        return BrainV2Alert.builder()
            .connectionId(connectionId)
            .alertType(AlertType.SIMILAR_WORKLOAD_FOUND)
            .severity(Severity.INFO)
            .title("Similar Workload Discovered")
            .message(String.format(
                "Found a similar workload pattern in connection %s (similarity: %.1f%%). " +
                "Knowledge transfer may improve recommendations.",
                similarConnectionId, similarityScore * 100
            ))
            .contextData(Map.of(
                "similarConnectionId", similarConnectionId,
                "similarityScore", similarityScore
            ))
            .recommendedAction("Consider applying successful configurations from the similar workload")
            .build();
    }
}
