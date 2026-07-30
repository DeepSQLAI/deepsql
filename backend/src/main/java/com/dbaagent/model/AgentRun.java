package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_runs", indexes = {
    @Index(name = "idx_agent_runs_chat_created", columnList = "chat_id, created_at"),
    @Index(name = "idx_agent_runs_connection_created", columnList = "connection_id, created_at")
})
@Data
public class AgentRun {
    @Id
    private String id;

    @Column(name = "chat_id")
    private String chatId;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false)
    private String intent;

    @Column(columnDefinition = "TEXT")
    private String goal;

    @Column(name = "plan_summary", columnDefinition = "TEXT")
    private String planSummary;

    @Column(name = "plan_tasks_json", columnDefinition = "TEXT")
    private String planTasksJson;

    @Column(nullable = false)
    private String status;

    private Double confidence;

    @Column(name = "final_message", columnDefinition = "TEXT")
    private String finalMessage;

    @Column(name = "user_message_id")
    private String userMessageId;

    @Column(name = "assistant_message_id")
    private String assistantMessageId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

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
