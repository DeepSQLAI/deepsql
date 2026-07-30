package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_performance_baseline")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPerformanceBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(name = "normalized_query", columnDefinition = "TEXT")
    private String normalizedQuery;

    // Baseline metrics
    @Column(name = "avg_execution_time_ms", nullable = false)
    private Double avgExecutionTimeMs;

    @Column(name = "p50_execution_time_ms")
    private Double p50ExecutionTimeMs;

    @Column(name = "p95_execution_time_ms")
    private Double p95ExecutionTimeMs;

    @Column(name = "p99_execution_time_ms")
    private Double p99ExecutionTimeMs;

    @Column(name = "min_execution_time_ms")
    private Double minExecutionTimeMs;

    @Column(name = "max_execution_time_ms")
    private Double maxExecutionTimeMs;

    @Column(name = "std_dev_execution_time_ms")
    private Double stdDevExecutionTimeMs;

    // Sample size
    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    @Column(name = "first_seen", nullable = false)
    private LocalDateTime firstSeen;

    @Column(name = "last_seen", nullable = false)
    private LocalDateTime lastSeen;

    // Baseline calculation
    @Column(name = "baseline_calculated_at", nullable = false)
    private LocalDateTime baselineCalculatedAt;

    @Column(name = "baseline_window_days")
    private Integer baselineWindowDays = 7;

    @PrePersist
    protected void onCreate() {
        if (baselineCalculatedAt == null) {
            baselineCalculatedAt = LocalDateTime.now();
        }
        if (baselineWindowDays == null) {
            baselineWindowDays = 7;
        }
    }
}
