package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_observations", indexes = {
    @Index(name = "idx_agent_observations_run_created", columnList = "run_id, created_at")
})
@Data
public class AgentObservationEntity {
    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "step_id")
    private String stepId;

    @Column(name = "observation_type", nullable = false)
    private String observationType;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "data_json", columnDefinition = "TEXT")
    private String dataJson;

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
