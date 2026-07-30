package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "connection_access_grant",
    uniqueConstraints = @UniqueConstraint(
        name = "idx_connection_access_grant_connection_user",
        columnNames = {"connection_id", "username"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionAccessGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 32)
    private ConnectionAccessLevel accessLevel;

    @Column(name = "granted_by", nullable = false)
    private String grantedBy;

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
