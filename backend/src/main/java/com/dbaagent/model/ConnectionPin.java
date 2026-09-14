package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One user's default database connection.
 *
 * <p>The pin is deliberately <em>per user</em> rather than a flag on
 * {@link DatabaseConnection}. A connection can be shared with several people through
 * {@code connection_access_grant}, and a default is a personal preference — one user's
 * choice must not decide what everyone else opens on. A column on the connection row
 * would also put the setting out of reach of exactly the people who need it: a shared
 * connection has {@code canManageConfig == false} for its recipients, so they could
 * never pin the connection they use every day.
 *
 * <p>At most one row per user — the unique constraint on {@code username} is what makes
 * "always the default" true rather than merely intended. Pinning a second connection
 * moves the pin instead of creating a second one; see
 * {@code ConnectionPinService.pin}.
 *
 * <p>{@code connection_id} carries no foreign key, matching
 * {@link ConnectionAccessGrant}. {@code ConnectionController.deleteConnection} clears
 * pins alongside grants; a pin that outlives its connection is inert anyway, since the
 * flag is only ever computed for connections the caller can already see.
 */
@Entity
@Table(
    name = "connection_pin",
    uniqueConstraints = @UniqueConstraint(
        name = "ux_connection_pin_username",
        columnNames = {"username"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionPin {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

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
