package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "saved_dashboards", indexes = {
    @Index(name = "idx_saved_dashboards_connection_id", columnList = "connectionId"),
    @Index(name = "idx_saved_dashboards_user_id", columnList = "userId"),
    @Index(name = "idx_saved_dashboards_is_favorite", columnList = "isFavorite"),
    @Index(name = "idx_saved_dashboards_created_at", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavedDashboard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String connectionId;

    @Column
    private String userId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String dashboardConfig;

    // The dashboard's build/edit chat thread (JSON array of {role,text}), persisted
    // so the conversation survives a refresh. One thread per dashboard.
    @Column(columnDefinition = "TEXT")
    private String chatMessages;

    @Column(length = 500)
    private String tags;

    @Column(nullable = false)
    private Boolean isFavorite = false;

    // Sharing: a random URL-safe token for the public link, and a gate that can
    // revoke it without discarding the token. Both null/false until the owner
    // publishes the dashboard to the web.
    @Column(length = 64, unique = true)
    private String shareToken;

    @Column(nullable = false)
    private Boolean isPublic = false;

    // Optional password gate for the public link (BCrypt hash). Never serialized;
    // the UI only learns whether one is set via sharePasswordSet below.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(length = 100)
    private String sharePasswordHash;

    /** Exposed to the authed UI so Share can show "password protected" state. */
    @Transient
    @com.fasterxml.jackson.annotation.JsonProperty("sharePasswordSet")
    public boolean isSharePasswordSet() {
        return sharePasswordHash != null && !sharePasswordHash.isBlank();
    }

    @Column(length = 255)
    private String folder;

    // Server-owned "is a generation turn in flight for this dashboard" marker.
    // Set to RUNNING the instant a chat submit is accepted (before the slow
    // agent work starts) and back to IDLE when it finishes — from the backend
    // code path itself, regardless of whether the SSE client that started it
    // is still connected. Lets a reload mid-generation distinguish "still
    // working" from "answer's ready" without needing to have stayed connected.
    // See SavedDashboardService.beginGenerationTurn/appendAgentReply/
    // completeBuildTurn/appendErrorReply.
    @Column(nullable = false, length = 16)
    private String generationStatus = "IDLE";

    // When the current RUNNING turn started, so a client can tell a live
    // generation from one abandoned by a backend crash (see
    // SavedDashboardService.STALE_RUNNING_THRESHOLD).
    @Column
    private LocalDateTime generationStartedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // Jackson deserializes create/update bodies via Lombok's all-args constructor
    // (Spring's parameter-names module), which bypasses the field defaults and
    // leaves these NOT-NULL booleans null when the client omits them. Coerce here
    // so inserts/updates never violate the constraint.
    @PrePersist
    @PreUpdate
    void applyBooleanDefaults() {
        if (isPublic == null) isPublic = false;
        if (isFavorite == null) isFavorite = false;
        if (generationStatus == null) generationStatus = "IDLE";
    }
}
