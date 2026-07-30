package com.dbaagent.model.code;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "code_scan_job", indexes = {
    @Index(name = "idx_code_scan_job_source", columnList = "sourceId,createdAt"),
    @Index(name = "idx_code_scan_job_connection_status", columnList = "connectionId,status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String sourceId;

    @Column(nullable = false)
    private String connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    @Builder.Default
    private Integer progress = 0;

    @Column(length = 120)
    private String currentStep;

    @Column(nullable = false)
    @Builder.Default
    private Integer filesTotal = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer filesParsed = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer chunksSent = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer suggestionsEmitted = 0;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime completedAt;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 120)
    private String triggeredBy;

    /** Snapshot of effective focus (user + ambiguity) at the time this job ran. */
    @Column(columnDefinition = "TEXT")
    private String focusText;

    @Column(length = 64)
    private String focusHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = Status.PENDING;
        }
    }

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
