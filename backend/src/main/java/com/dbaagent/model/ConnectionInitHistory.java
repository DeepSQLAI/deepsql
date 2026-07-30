package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "connection_init_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConnectionInitHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "final_stage", nullable = false)
    @Enumerated(EnumType.STRING)
    private InitStage finalStage;

    @Column(name = "progress_percent", nullable = false)
    private Integer progressPercent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_timings", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, ConnectionInitStatus.StageTimingEntry> stageTimings = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_details", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Map<String, Object>> stageDetails = new HashMap<>();

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
