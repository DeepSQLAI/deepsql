package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_performance_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPerformanceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "normalized_query", columnDefinition = "TEXT")
    private String normalizedQuery;

    // Performance metrics
    @Column(name = "execution_time_ms", nullable = false)
    private Double executionTimeMs;

    @Column(name = "rows_examined")
    private Long rowsExamined;

    @Column(name = "rows_sent")
    private Long rowsSent;

    @Column(name = "cpu_time_ms")
    private Double cpuTimeMs;

    @Column(name = "io_wait_ms")
    private Double ioWaitMs;

    @Column(name = "lock_wait_ms")
    private Double lockWaitMs;

    // Execution context
    @Column(name = "database_name")
    private String databaseName;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "client_host")
    private String clientHost;

    // Metadata
    @Column(name = "execution_timestamp", nullable = false)
    private LocalDateTime executionTimestamp;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    protected void onCreate() {
        if (recordedAt == null) {
            recordedAt = LocalDateTime.now();
        }
        if (executionTimestamp == null) {
            executionTimestamp = LocalDateTime.now();
        }
    }
}
