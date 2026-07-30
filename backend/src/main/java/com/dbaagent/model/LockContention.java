package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "lock_contentions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockContention {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    // Blocking session info
    @Column(nullable = false)
    private String blockingPid;

    @Column(columnDefinition = "TEXT")
    private String blockingQuery;

    private String blockingUser;
    private String blockingApplication;
    private String blockingState;
    private LocalDateTime blockingStartTime;

    // Blocked session info
    @Column(nullable = false)
    private String blockedPid;

    @Column(columnDefinition = "TEXT")
    private String blockedQuery;

    private String blockedUser;
    private String blockedApplication;
    private String blockedState;
    private LocalDateTime blockedStartTime;

    // Lock details
    @Column(nullable = false)
    private String lockType;

    private String lockMode;
    private String lockTarget; // table, row, etc.
    private String databaseName;
    private String schemaName;
    private String tableName;

    // Wait information
    private String waitEvent;
    private String waitEventType;
    private Long waitDurationSeconds;

    // Detection metadata
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private Boolean resolved;

    private LocalDateTime resolvedAt;
    private String resolutionMethod; // "AUTO_RESOLVED", "MANUAL_KILL", "QUERY_COMPLETED"

    @Enumerated(EnumType.STRING)
    private Severity severity;

    public enum Severity {
        CRITICAL,  // > 60 seconds
        HIGH,      // 30-60 seconds
        MEDIUM,    // 10-30 seconds
        LOW        // < 10 seconds
    }

    public static Severity calculateSeverity(Long waitDurationSeconds) {
        if (waitDurationSeconds == null || waitDurationSeconds < 10) {
            return Severity.LOW;
        } else if (waitDurationSeconds < 30) {
            return Severity.MEDIUM;
        } else if (waitDurationSeconds < 60) {
            return Severity.HIGH;
        } else {
            return Severity.CRITICAL;
        }
    }
}
