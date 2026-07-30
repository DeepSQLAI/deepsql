package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_run_steps", indexes = {
    @Index(name = "idx_agent_run_steps_run_step", columnList = "run_id, step_index")
})
@Data
public class AgentRunStep {
    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    @Column(name = "step_key")
    private String stepKey;

    @Column(name = "task_id")
    private String taskId;

    @Column(nullable = false)
    private String title;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "step_kind")
    private String stepKind;

    @Column(nullable = false)
    private String status;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "depends_on_json", columnDefinition = "TEXT")
    private String dependsOnJson;

    @Column(name = "executed_sql", columnDefinition = "TEXT")
    private String executedSql;

    @Column(name = "executed_sql_json", columnDefinition = "TEXT")
    private String executedSqlJson;

    @Column(name = "artifacts_json", columnDefinition = "TEXT")
    private String artifactsJson;

    private Double confidence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
