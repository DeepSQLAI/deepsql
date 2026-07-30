package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "connection_init_status")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConnectionInitStatus {

    @Id
    @Column(name = "connection_id")
    private String connectionId;

    @Column(name = "current_stage", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private InitStage currentStage = InitStage.SCHEMA_SCAN;

    @Column(name = "progress_percent", nullable = false)
    @Builder.Default
    private Integer progressPercent = 0;

    @Column(name = "stage_message", length = 500)
    private String stageMessage;

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_timings", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, StageTimingEntry> stageTimings = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_details", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Map<String, Object>> stageDetails = new HashMap<>();

    @Column(name = "cancel_requested", nullable = false)
    @Builder.Default
    private Boolean cancelRequested = false;

    @Column(name = "active_run_id")
    private java.util.UUID activeRunId;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) startedAt = LocalDateTime.now();
    }

    public record StageTimingEntry(String startedAt, String endedAt, long durationMs) {}
}
