package com.dbaagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * A DeepSQL Agent conversation owned by a user — the identity-keyed index that
 * lets the same user resume their agent chats from any device.
 *
 * <p>{@code agentSessionId} points at the agent's server-side reasoning context
 * (persisted in the per-profile state.db); {@code transcript} mirrors the
 * rendered messages so any device can display the history without depending on
 * the webui's profile-scoped session reads.
 */
@Entity
@Table(name = "agent_conversation", indexes = {
    @Index(name = "idx_agent_conv_user_conn", columnList = "user_id, connection_id, last_message_at"),
    @Index(name = "idx_agent_conv_session", columnList = "agent_session_id")
})
@Data
public class AgentConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "connection_id")
    private String connectionId;

    @Column(name = "agent_session_id", nullable = false)
    private String agentSessionId;

    @Column
    private String title;

    /** Mirrored rendered messages (the panel's {role, content, tools} bubbles). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode transcript;

    @Column(nullable = false)
    private boolean archived = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
