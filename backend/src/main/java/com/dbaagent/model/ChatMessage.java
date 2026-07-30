package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages", indexes = {
    @Index(name = "idx_message_chat", columnList = "chat_id"),
    @Index(name = "idx_message_created", columnList = "created_at")
})
@Data
public class ChatMessage {
    @Id
    private String id;

    @Column(name = "chat_id", nullable = false)
    private String chatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String sql;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    public enum MessageRole {
        USER,
        ASSISTANT,
        SYSTEM
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
    }
}
