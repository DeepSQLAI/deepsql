package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity class for storing Slow Query analysis history
 * Persists analysis results to H2 database for cross-session access
 */
@Entity
@Table(name = "slow_query_history", indexes = {
    @Index(name = "idx_slow_connection_id", columnList = "connectionId"),
    @Index(name = "idx_slow_created_at", columnList = "createdAt"),
    @Index(name = "idx_slow_user_id", columnList = "userId"),
    @Index(name = "idx_slow_time_range", columnList = "timeRange")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowQueryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column
    private String userId;  // Optional: for multi-user scenarios

    @Column(nullable = false)
    private String timeRange;  // LAST_HOUR, LAST_24_HOURS, etc.

    @Column
    private Double slowQueryThresholdMs;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String analysisData;  // JSON string of full SlowQueryAnalysis object

    // Key metrics for quick filtering/sorting
    @Column
    private Long totalSlowQueries;

    @Column
    private String overallHealth;  // EXCELLENT, GOOD, FAIR, POOR, CRITICAL

    @Column
    private Long criticalCount;

    @Column
    private Long highCount;

    @Column
    private Double totalDatabaseTimeMs;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
