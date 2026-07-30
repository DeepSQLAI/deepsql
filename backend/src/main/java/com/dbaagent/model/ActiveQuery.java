package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "active_queries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    // Session information
    @Column(nullable = false)
    private String pid;

    @Column(name = "\"user\"")
    private String user;

    private String database;
    private String applicationName;
    private String clientAddress;
    private Integer clientPort;

    // Query information
    @Column(columnDefinition = "TEXT")
    private String queryText;

    @Column(nullable = false)
    private String state; // active, idle, idle in transaction, waiting, etc.

    private LocalDateTime queryStart;
    private LocalDateTime stateChange;
    private LocalDateTime backendStart;
    private LocalDateTime transactionStart;

    // Performance metrics
    private Long durationSeconds;
    private Long transactionDurationSeconds;

    // Wait information
    private String waitEvent;
    private String waitEventType;

    // Resource usage (if available)
    private Long cpuPercent;
    private Long memoryBytes;
    private Long tempFilesBytes;
    private Long readBytes;
    private Long writeBytes;

    // Query type classification
    private String queryType; // SELECT, INSERT, UPDATE, DELETE, DDL, etc.

    // Metadata
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime capturedAt;

    @Column(nullable = false)
    private Boolean isBlocked;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    public enum Priority {
        CRITICAL,  // Long-running (>5 min) or resource-intensive
        HIGH,      // Medium duration (1-5 min)
        MEDIUM,    // Short duration (10s-1min)
        LOW        // Very short (<10s)
    }

    public static Priority calculatePriority(Long durationSeconds, String state) {
        if (durationSeconds == null || !"active".equalsIgnoreCase(state)) {
            return Priority.LOW;
        }

        if (durationSeconds >= 300) { // 5 minutes
            return Priority.CRITICAL;
        } else if (durationSeconds >= 60) { // 1 minute
            return Priority.HIGH;
        } else if (durationSeconds >= 10) { // 10 seconds
            return Priority.MEDIUM;
        } else {
            return Priority.LOW;
        }
    }

    public static String classifyQueryType(String queryText) {
        if (queryText == null || queryText.trim().isEmpty()) {
            return "UNKNOWN";
        }

        String normalized = queryText.trim().toUpperCase();

        if (normalized.startsWith("SELECT") || normalized.startsWith("WITH")) {
            return "SELECT";
        } else if (normalized.startsWith("INSERT")) {
            return "INSERT";
        } else if (normalized.startsWith("UPDATE")) {
            return "UPDATE";
        } else if (normalized.startsWith("DELETE")) {
            return "DELETE";
        } else if (normalized.startsWith("CREATE") || normalized.startsWith("ALTER") ||
                   normalized.startsWith("DROP") || normalized.startsWith("TRUNCATE")) {
            return "DDL";
        } else if (normalized.startsWith("BEGIN") || normalized.startsWith("COMMIT") ||
                   normalized.startsWith("ROLLBACK")) {
            return "TRANSACTION";
        } else if (normalized.startsWith("VACUUM") || normalized.startsWith("ANALYZE")) {
            return "MAINTENANCE";
        } else {
            return "OTHER";
        }
    }
}
