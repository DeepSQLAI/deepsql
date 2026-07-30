package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "slack_thread_session", uniqueConstraints = {
    @UniqueConstraint(name = "uk_slack_thread_session_thread", columnNames = {"team_id", "channel_id", "root_thread_ts"})
})
@Data
public class SlackThreadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false, length = 64)
    private String teamId;

    @Column(name = "channel_id", nullable = false, length = 64)
    private String channelId;

    @Column(name = "root_thread_ts", nullable = false, length = 64)
    private String rootThreadTs;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "chat_id", nullable = false, length = 36)
    private String chatId;

    /** DeepSQL Agent session id for this thread (slack.brain=agent path). */
    @Column(name = "agent_session_id", length = 64)
    private String agentSessionId;

    @Column(name = "slack_user_id", length = 64)
    private String slackUserId;

    @Column(name = "deepsql_username", length = 255)
    private String deepsqlUsername;

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        lastUsedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        LocalDateTime now = LocalDateTime.now();
        updatedAt = now;
        lastUsedAt = now;
    }
}
