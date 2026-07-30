package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "playbook_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybookRun {

    @Id
    private String id;

    @Column(name = "playbook_id", nullable = false)
    private String playbookId;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private RunStatus status;

    @Column(name = "trigger_type", length = 20)
    @Enumerated(EnumType.STRING)
    private TriggerType triggerType;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "steps_total")
    private Integer stepsTotal = 0;

    @Column(name = "steps_completed")
    private Integer stepsCompleted = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private List<StepResult> findings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private List<Map<String, Object>> recommendations;

    @Column(name = "alerts_triggered")
    private Integer alertsTriggered = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public enum RunStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum TriggerType {
        MANUAL,
        SCHEDULED,
        ALERT
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepResult {
        private String stepName;
        private String tool;
        private String status; // 'success', 'warning', 'error'
        private Map<String, Object> data;
        private List<String> issues;
        private Long executionTimeMs;
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }

    public void markCompleted() {
        this.status = RunStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        if (startedAt != null) {
            this.durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
        }
    }

    public void markFailed(String errorMessage) {
        this.status = RunStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = errorMessage;
        if (startedAt != null) {
            this.durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
        }
    }
}
