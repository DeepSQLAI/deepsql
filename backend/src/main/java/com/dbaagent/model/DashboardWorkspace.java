package com.dbaagent.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A named group of dashboards within one connection, with its own member list.
 *
 * <p>Workspace access <em>narrows</em>, never widens: a dashboard in a workspace is
 * visible only to someone who both has read access to the connection (the existing
 * per-connection ACL, unchanged) and is a member of the workspace. Admins bypass the
 * membership half, matching how they already bypass connection grants. A dashboard with
 * no workspace behaves exactly as it does today.
 *
 * <p>Scoped to a connection because every dashboard already is — a workspace spanning
 * connections would have to re-check the connection ACL per dashboard anyway, so it
 * would group things the ACL then pulls back apart.
 */
@Entity
@Table(name = "dashboard_workspaces", indexes = {
    @Index(name = "idx_dashboard_workspaces_connection", columnList = "connection_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "ux_dashboard_workspaces_conn_name", columnNames = {"connection_id", "name"})
})
public class DashboardWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 500)
    private String description;

    /** Short colour token the UI uses for the workspace chip. */
    @Column(length = 32)
    private String color;

    /** Username of the creator; always an implicit MANAGER member. */
    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
