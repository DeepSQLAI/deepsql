package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Global digest configuration (singleton row, id=1).
 *
 * <p>This table maintains backward compatibility with the original singleton design.
 * The row with id=1 serves as the global default when a user has no specific
 * {@link UserDigestPreference} configured.
 *
 * <h3>Resolution Order</h3>
 * <ol>
 *   <li>User-specific {@link UserDigestPreference} with custom cronExpression</li>
 *   <li>User-specific {@link UserDigestPreference} without cronExpression → uses this global cron</li>
 *   <li>No preferences at all → legacy broadcast using this global cron</li>
 * </ol>
 */
@Entity
@Table(name = "slack_digest_config")
@Data
@NoArgsConstructor
public class SlackDigestConfig {

    @Id
    private Long id = 1L;

    @Column(nullable = false)
    private String cronExpression = "0 0 9 * * *";

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Marks this as the global default configuration.
     * Always true for the singleton row (id=1).
     */
    @Column(name = "is_global_default", nullable = false)
    private boolean globalDefault = true;
}
