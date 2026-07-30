package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "slack_user_connection_binding", uniqueConstraints = {
    @UniqueConstraint(name = "uk_slack_user_connection_binding_team_user", columnNames = {"team_id", "slack_user_id"})
})
@Data
public class SlackUserConnectionBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false, length = 64)
    private String teamId;

    @Column(name = "slack_user_id", nullable = false, length = 64)
    private String slackUserId;

    @Column(name = "default_connection_id", nullable = false, length = 36)
    private String defaultConnectionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
