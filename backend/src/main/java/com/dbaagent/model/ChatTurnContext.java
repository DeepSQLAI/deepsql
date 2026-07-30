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
@Table(name = "chat_turn_context", indexes = {
    @Index(name = "idx_chat_turn_context_chat_created", columnList = "chat_id, created_at"),
    @Index(name = "idx_chat_turn_context_connection_created", columnList = "connection_id, created_at"),
    @Index(name = "idx_chat_turn_context_parent", columnList = "parent_context_id"),
    @Index(name = "idx_chat_turn_context_assistant_message", columnList = "assistant_message_id", unique = true)
})
@Data
public class ChatTurnContext {

    @Id
    private String id;

    @Column(name = "chat_id", nullable = false)
    private String chatId;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "user_message_id", nullable = false)
    private String userMessageId;

    @Column(name = "assistant_message_id", nullable = false)
    private String assistantMessageId;

    @Column(name = "parent_context_id")
    private String parentContextId;

    @Column(name = "state_status", nullable = false, length = 32)
    private String stateStatus;

    @Column(name = "route_type", length = 64)
    private String routeType;

    @Column(length = 128)
    private String intent;

    @Column(name = "anchor_question", columnDefinition = "TEXT")
    private String anchorQuestion;

    @Column(name = "current_question", nullable = false, columnDefinition = "TEXT")
    private String currentQuestion;

    @Column(name = "question_summary", columnDefinition = "TEXT")
    private String questionSummary;

    @Column(name = "answer_summary", columnDefinition = "TEXT")
    private String answerSummary;

    @Column(name = "chain_summary", columnDefinition = "TEXT")
    private String chainSummary;

    @Column(name = "resolved_context_json", columnDefinition = "TEXT")
    private String resolvedContextJson;

    @Column(name = "selected_entities_json", columnDefinition = "TEXT")
    private String selectedEntitiesJson;

    @Column(name = "result_summary_json", columnDefinition = "TEXT")
    private String resultSummaryJson;

    @Column(name = "source_sql", columnDefinition = "TEXT")
    private String sourceSql;

    @Column(name = "topic_signature", columnDefinition = "TEXT")
    private String topicSignature;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
