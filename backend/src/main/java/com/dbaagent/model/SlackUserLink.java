package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "slack_user_link", uniqueConstraints = {
    @UniqueConstraint(name = "uk_slack_user_link_team_user", columnNames = {"team_id", "slack_user_id"})
})
@Data
public class SlackUserLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false, length = 64)
    private String teamId;

    @Column(name = "slack_user_id", nullable = false, length = 64)
    private String slackUserId;

    @Column(name = "slack_email", length = 255)
    private String slackEmail;

    @Column(name = "slack_display_name", length = 255)
    private String slackDisplayName;

    @Column(name = "deepsql_username", nullable = false, length = 255)
    private String deepsqlUsername;

    @Column(name = "link_status", nullable = false, length = 32)
    private String linkStatus;

    @Column(name = "linked_at")
    private LocalDateTime linkedAt;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

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

    @Transient
    public SlackUserLinkStatus getLinkStatusEnum() {
        return linkStatus == null ? null : SlackUserLinkStatus.valueOf(linkStatus);
    }

    public void setLinkStatusEnum(SlackUserLinkStatus status) {
        this.linkStatus = status == null ? null : status.name();
    }
}
