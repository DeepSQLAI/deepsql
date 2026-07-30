package com.dbaagent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * Represents an async log ingestion job with progress tracking
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionJob {

    public enum Status {
        PENDING,      // Job created, not yet started
        RUNNING,      // Job is currently executing
        COMPLETED,    // Job finished successfully
        FAILED,       // Job failed with error
        CANCELLED,    // Job was cancelled by user
        INTERRUPTED   // Job was interrupted by server restart (can be resumed)
    }

    private String jobId;
    private String connectionId;
    private Status status;

    // Progress tracking
    private int progress;           // 0-100 percentage
    private String currentStep;     // Human-readable current step
    private int eventsProcessed;    // Number of log events processed so far
    private int totalStreams;       // Total log streams to process
    private int streamsProcessed;   // Streams processed so far

    // Timing
    private Instant startedAt;
    private Instant completedAt;

    // Result
    private String message;         // Success/error message
    private String historyId;       // ID of saved SlowQueryHistory if successful
    private int slowQueriesFound;   // Number of slow queries found

    // Cancellation support
    private volatile boolean cancelRequested;

    // Truncation flag - set when log was size-capped and results are partial
    private boolean truncated;

    // Configuration used
    private String timeRange;
    private String providerType;
    private String logSource;       // Log group or bucket name

    /**
     * Check if the job is in a terminal state
     */
    public boolean isTerminal() {
        return status == Status.COMPLETED ||
               status == Status.FAILED ||
               status == Status.CANCELLED;
    }

    /**
     * Check if cancellation was requested
     */
    public boolean isCancelRequested() {
        return cancelRequested;
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
    }

    /**
     * Mark job as completed successfully
     */
    public void complete(String message, String historyId, int slowQueriesFound) {
        this.status = Status.COMPLETED;
        this.progress = 100;
        this.currentStep = "Completed";
        this.completedAt = Instant.now();
        this.message = message;
        this.historyId = historyId;
        this.slowQueriesFound = slowQueriesFound;
    }

    /**
     * Mark job as completed with truncation flag
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
        this.message = errorMessage;
        this.currentStep = "Failed";
    }

    /**
     * Mark job as cancelled
     */
    public void cancel() {
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
        this.message = "Ingestion cancelled by user";
        this.currentStep = "Cancelled";
    }
}
