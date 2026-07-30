package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * Persistent ingestion job entity - survives server restarts.
 * Tracks progress, checkpoints, and allows job resumption.
 */
@Entity
@Table(name = "ingestion_jobs", indexes = {
    @Index(name = "idx_ingestion_job_connection", columnList = "connectionId"),
    @Index(name = "idx_ingestion_job_status", columnList = "status"),
    @Index(name = "idx_ingestion_job_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionJobEntity {

    public enum Status {
        PENDING,      // Job created, not yet started
        RUNNING,      // Job is currently executing
        COMPLETED,    // Job finished successfully
        FAILED,       // Job failed with error
        CANCELLED,    // Job was cancelled by user
        INTERRUPTED   // Job was interrupted by server restart (can be resumed)
    }

    @Id
    @Column(length = 36)
    private String jobId;

    @Column(nullable = false, length = 36)
    private String connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    // Progress tracking
    @Column(nullable = false)
    private int progress;           // 0-100 percentage

    @Column(length = 500)
    private String currentStep;     // Human-readable current step

    @Column(nullable = false)
    private long bytesProcessed;    // Bytes of log data processed

    @Column(nullable = false)
    private int slowQueriesFound;   // Slow queries found so far

    @Column(nullable = false)
    private int totalStreams;       // Total log streams to process (CloudWatch)

    @Column(nullable = false)
    private int streamsProcessed;   // Streams processed so far

    // Timing
    @Column(nullable = false)
    private Instant createdAt;

    private Instant startedAt;
    private Instant completedAt;
    private Instant lastHeartbeat;  // Last activity timestamp for detecting stale jobs

    // Result
    @Column(length = 2000)
    private String message;         // Success/error message

    @Column(length = 36)
    private String historyId;       // ID of saved SlowQueryHistory if successful

    // Cancellation support
    @Column(nullable = false)
    private boolean cancelRequested;

    // Truncation flag - set when log was size-capped and results are partial
    @Column(nullable = false)
    private boolean truncated;

    // Configuration used (for resume capability)
    @Column(length = 50)
    private String timeRange;

    @Column(length = 50)
    private String providerType;

    @Column(length = 500)
    private String logSource;       // Log group or bucket name

    // Checkpoint for resume (CloudWatch-specific)
    private Instant lastProcessedEventTime;  // Timestamp of last processed event

    @Column(length = 500)
    private String lastProcessedStreamName;  // Name of last processed stream

    @Column(nullable = false)
    private int resumeAttempts;     // Number of times this job has been resumed

    // Server instance tracking (for multi-instance deployments)
    @Column(length = 100)
    private String serverInstanceId;

    /**
     * Check if the job is in a terminal state
     */
    public boolean isTerminal() {
        return status == Status.COMPLETED ||
               status == Status.FAILED ||
               status == Status.CANCELLED;
    }

    /**
     * Check if the job can be resumed
     */
    public boolean isResumable() {
        return status == Status.INTERRUPTED && resumeAttempts < 3;
    }

    /**
     * Request cancellation of this job
     */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /**
     * Update progress with a step description
     */
    public void updateProgress(int progress, String step) {
        this.progress = Math.min(100, Math.max(0, progress));
        this.currentStep = step;
        this.lastHeartbeat = Instant.now();
    }

    /**
     * Mark job as completed successfully
     */
    public void complete(String message, String historyId, int slowQueriesFound) {
        this.status = Status.COMPLETED;
        this.progress = 100;
        this.currentStep = "Completed";
        this.completedAt = Instant.now();
        this.lastHeartbeat = Instant.now();
        this.message = message;
        this.historyId = historyId;
        this.slowQueriesFound = slowQueriesFound;
    }

    /**
     * Mark job as completed with the truncation flag in one atomic transition,
     * so callers never have to set {@code truncated} separately (which risks
     * clobbering an already-set flag).
     */
    public void complete(String message, String historyId, int slowQueriesFound, boolean truncated) {
        complete(message, historyId, slowQueriesFound);
        this.truncated = truncated;
    }

    /**
     * Mark job as failed
     */
    public void fail(String errorMessage) {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
        this.lastHeartbeat = Instant.now();
        this.message = truncateMessage(errorMessage);
        this.currentStep = "Failed";
    }

    /**
     * Mark job as cancelled
     */
    public void cancel() {
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
        this.lastHeartbeat = Instant.now();
        this.message = "Ingestion cancelled by user";
        this.currentStep = "Cancelled";
    }

    /**
     * Mark job as interrupted (server restart)
     */
    public void markInterrupted() {
        this.status = Status.INTERRUPTED;
        this.lastHeartbeat = Instant.now();
        this.message = "Job interrupted by server restart. Can be resumed.";
        this.currentStep = "Interrupted";
    }

    /**
     * Prepare job for resume
     */
    public void prepareForResume() {
        this.status = Status.PENDING;
        this.resumeAttempts++;
        this.lastHeartbeat = Instant.now();
        this.currentStep = "Resuming...";
        this.message = null;
    }

    /**
     * Convert to DTO for API responses
     */
    public IngestionJob toDto() {
        return IngestionJob.builder()
            .jobId(jobId)
            .connectionId(connectionId)
            .status(IngestionJob.Status.valueOf(status.name()))
            .progress(progress)
            .currentStep(currentStep)
            .eventsProcessed((int) Math.min(Integer.MAX_VALUE, bytesProcessed))
            .totalStreams(totalStreams)
            .streamsProcessed(streamsProcessed)
            .startedAt(startedAt)
            .completedAt(completedAt)
            .message(message)
            .historyId(historyId)
            .slowQueriesFound(slowQueriesFound)
            .cancelRequested(cancelRequested)
            .timeRange(timeRange)
            .providerType(providerType)
            .logSource(logSource)
            .truncated(truncated)
            .build();
    }

    private String truncateMessage(String msg) {
        if (msg == null) return null;
        return msg.length() > 2000 ? msg.substring(0, 1997) + "..." : msg;
    }
}
