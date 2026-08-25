package com.dbaagent.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One user's membership of one {@link DashboardWorkspace}.
 *
 * <p>Keyed by username rather than user id to match how the rest of the access layer
 * identifies actors ({@code ConnectionAccessGrant}, {@code AccessControlService}), so an
 * impersonated ("View as") session resolves membership as the target user without a
 * second lookup.
 */
@Entity
@Table(name = "dashboard_workspace_members", indexes = {
    @Index(name = "idx_dashboard_ws_members_workspace", columnList = "workspace_id"),
    @Index(name = "idx_dashboard_ws_members_username", columnList = "username")
}, uniqueConstraints = {
    @UniqueConstraint(name = "ux_dashboard_ws_member", columnNames = {"workspace_id", "username"})
})
public class DashboardWorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, length = 255)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_role", nullable = false, length = 32)
    private DashboardWorkspaceRole workspaceRole = DashboardWorkspaceRole.VIEWER;

    @Column(name = "added_by", length = 255)
    private String addedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public DashboardWorkspaceRole getWorkspaceRole() { return workspaceRole; }
    public void setWorkspaceRole(DashboardWorkspaceRole workspaceRole) { this.workspaceRole = workspaceRole; }

    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String addedBy) { this.addedBy = addedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
