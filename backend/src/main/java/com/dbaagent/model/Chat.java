package com.dbaagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "chats", indexes = {
    @Index(name = "idx_chat_project", columnList = "project_id"),
    @Index(name = "idx_chat_connection", columnList = "connection_id"),
    @Index(name = "idx_chat_owner_connection", columnList = "owner_username,connection_id")
})
@Data
public class Chat {
    @Id
    private String id;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @JsonIgnore
    @Column(name = "owner_username")
    private String ownerUsername;

    @Column(nullable = false)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        lastMessageAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
