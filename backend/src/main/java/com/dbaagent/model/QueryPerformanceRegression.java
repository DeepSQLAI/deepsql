package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_performance_regression")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPerformanceRegression {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(name = "normalized_query", columnDefinition = "TEXT")
    private String normalizedQuery;

    // Regression details
    @Column(name = "severity", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(name = "regression_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private RegressionType regressionType;

    // Performance comparison
    @Column(name = "baseline_avg_ms", nullable = false)
    private Double baselineAvgMs;

    @Column(name = "current_avg_ms", nullable = false)
    private Double currentAvgMs;

    @Column(name = "slowdown_factor", nullable = false)
    private Double slowdownFactor;

    @Column(name = "slowdown_percent", nullable = false)
    private Double slowdownPercent;

    // Detection metadata
    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "detection_window_start", nullable = false)
    private LocalDateTime detectionWindowStart;

    @Column(name = "detection_window_end", nullable = false)
    private LocalDateTime detectionWindowEnd;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    // Status
    @Column(name = "acknowledged")
    private Boolean acknowledged = false;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "resolved")
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @PrePersist
    protected void onCreate() {
        if (detectedAt == null) {
            detectedAt = LocalDateTime.now();
        }
        if (acknowledged == null) {
            acknowledged = false;
        }
        if (resolved == null) {
            resolved = false;
        }
    }

    public enum Severity {
        MINOR,      // 10-50% slower
        MODERATE,   // 50-100% slower
        SEVERE,     // 100-200% slower
        CRITICAL    // >200% slower
    }

    public enum RegressionType {
        SUDDEN_SPIKE,           // Immediate sharp increase
        GRADUAL_DEGRADATION,    // Slow increase over time
        ANOMALY                 // Statistical outlier
    }
}
