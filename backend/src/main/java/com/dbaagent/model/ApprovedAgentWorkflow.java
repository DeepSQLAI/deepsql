package com.dbaagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "approved_agent_workflows", indexes = {
    @Index(name = "idx_approved_agent_workflows_connection_intent", columnList = "connection_id,intent")
})
@Data
public class ApprovedAgentWorkflow {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String intent;

    @Column(name = "example_question", nullable = false, columnDefinition = "TEXT")
    private String exampleQuestion;

    @Column(name = "normalized_question", nullable = false, columnDefinition = "TEXT")
    private String normalizedQuestion;

    @Column(name = "question_signature", nullable = false, length = 128)
    private String questionSignature;

    @Column(name = "source_context_id")
    private String sourceContextId;

    @Column(name = "anchor_question", columnDefinition = "TEXT")
    private String anchorQuestion;

    @Column(name = "chain_summary", columnDefinition = "TEXT")
    private String chainSummary;

    @Column(name = "resolved_context_json", columnDefinition = "TEXT")
    private String resolvedContextJson;

    @Column(columnDefinition = "TEXT")
    private String goal;

    @Column(name = "plan_summary", columnDefinition = "TEXT")
    private String planSummary;

    @Column(name = "tools_json", columnDefinition = "TEXT")
    private String toolsJson;

    @Column(name = "step_params_json", columnDefinition = "TEXT")
    private String stepParamsJson;

    @Column(name = "helpful_count", nullable = false)
    private Integer helpfulCount;

    @Column(name = "average_confidence")
    private Double averageConfidence;

    @Column(name = "latest_agent_run_id")
    private String latestAgentRunId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_approved_at", nullable = false)
    private LocalDateTime lastApprovedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (lastApprovedAt == null) {
            lastApprovedAt = now;
        }
        if (helpfulCount == null || helpfulCount < 1) {
            helpfulCount = 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (lastApprovedAt == null) {
            lastApprovedAt = updatedAt;
        }
    }
}
