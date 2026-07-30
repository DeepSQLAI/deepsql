package com.dbaagent.controller;

import com.dbaagent.batch.BatchIngestionService;
import com.dbaagent.batch.BatchIngestionService.BatchIngestionStatus;
import com.dbaagent.dto.SlowLogIngestRequest;
import com.dbaagent.dto.SlowLogSourceConfigRequest;
import com.dbaagent.dto.SlowLogSourceConfigResponse;
import com.dbaagent.service.SlowLogIngestionService;
import com.dbaagent.service.SlowLogSourceConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/slow-log-source")
@RequiredArgsConstructor
@Slf4j
public class SlowLogSourceController {
    private final SlowLogSourceConfigService configService;
    private final SlowLogIngestionService ingestionService;
    private final BatchIngestionService batchIngestionService;

    @GetMapping("/{connectionId}")
    public ResponseEntity<SlowLogSourceConfigResponse> getConfig(@PathVariable String connectionId) {
        try {
            SlowLogSourceConfigResponse response = configService.getResponse(connectionId);
            if (response == null) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get slow log source config for connection {}: {}", connectionId, e.getMessage(), e);
            throw e;
        }
    }

    @PostMapping
    public ResponseEntity<SlowLogSourceConfigResponse> upsertConfig(
        @RequestBody SlowLogSourceConfigRequest request
    ) {
        return ResponseEntity.ok(configService.upsertConfig(request));
    }

    /**
     * Clone slow-log source config from one connection to another, decrypting
     * every secret with the source's AAD and re-encrypting with the target's.
     *
     * <p>Companion to {@code POST /connections/{id}/clone} — when a user adds
     * a read replica by cloning the master, that endpoint only clones the
     * connection itself; calling this afterwards copies over the
     * CloudWatch/S3/Datadog/etc. credentials and log-group config so the
     * replica's slow-log ingestion is immediately functional without
     * re-entering AWS creds.
     */
    @PostMapping("/{targetConnectionId}/clone-from/{sourceConnectionId}")
    public ResponseEntity<?> cloneFrom(
        @PathVariable String targetConnectionId,
        @PathVariable String sourceConnectionId
    ) {
        try {
            SlowLogSourceConfigResponse cloned = configService.cloneFrom(sourceConnectionId, targetConnectionId);
            if (cloned == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Source connection has no slow-log source config to clone"));
            }
            return ResponseEntity.ok(cloned);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to clone slow-log source config from {} to {}: {}",
                sourceConnectionId, targetConnectionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to clone slow-log source config: " + e.getMessage()));
        }
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingestNow(
        @RequestBody SlowLogIngestRequest request
    ) {
        SlowLogIngestionService.IngestResult result = ingestionService.ingestNowWithDetails(request);
        if (!result.success()) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", result.message()
            ));
        }
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", result.message(),
            "historyId", result.history() != null ? result.history().getId() : ""
        ));
    }

    /**
     * Start an async ingestion job using Spring Batch.
     * Returns immediately with job execution ID for polling.
     * Jobs are persisted in DB and survive server restarts.
     */
    @PostMapping("/ingest/async")
    public ResponseEntity<Map<String, Object>> startAsyncIngestion(
        @RequestBody SlowLogIngestRequest request
    ) {
        try {
            // Check if there's already an active job for this connection
            Optional<BatchIngestionStatus> activeJob = batchIngestionService.getActiveJobForConnection(request.getConnectionId());
            if (activeJob.isPresent()) {
                BatchIngestionStatus active = activeJob.get();
                if (active.isResumable()) {
                    return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", "A previous job was interrupted. Please resume it or start fresh.",
                        "jobId", active.jobExecutionId().toString(),
                        "canResume", true,
                        "status", active.status()
                    ));
                }
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "An ingestion job is already running for this connection",
                    "jobId", active.jobExecutionId().toString()
                ));
            }

            BatchIngestionStatus job = batchIngestionService.startJob(
                request.getConnectionId(),
                request.getTimeRange(),
                request.getMaxLogs() != null ? request.getMaxLogs().longValue() : null
            );

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Ingestion job started",
                "jobId", job.jobExecutionId().toString()
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to start async ingestion: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Get status of an ingestion job (Spring Batch execution)
     */
    @GetMapping("/ingest/status/{jobId}")
    public ResponseEntity<BatchIngestionStatus> getJobStatus(@PathVariable String jobId) {
        try {
            Long executionId = Long.parseLong(jobId);
            Optional<BatchIngestionStatus> status = batchIngestionService.getJobStatus(executionId);
            if (status.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(status.get());
        } catch (NumberFormatException e) {
            log.warn("Invalid job ID format: {}", jobId);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get active job for a connection (if any)
     */
    @GetMapping("/ingest/active/{connectionId}")
    public ResponseEntity<BatchIngestionStatus> getActiveJob(@PathVariable String connectionId) {
        Optional<BatchIngestionStatus> job = batchIngestionService.getActiveJobForConnection(connectionId);
        if (job.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(job.get());
    }

    /**
     * Stop/cancel a running ingestion job
     */
    @PostMapping("/ingest/cancel/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        try {
            Long executionId = Long.parseLong(jobId);
            boolean stopped = batchIngestionService.stopJob(executionId);
            if (!stopped) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Job not found or already completed"
                ));
            }
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Stop requested"
            ));
        } catch (NumberFormatException e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Invalid job ID format"
            ));
        }
    }

    /**
     * Resume/restart an interrupted or failed ingestion job.
     * Uses Spring Batch 6.0.2's restart() method which launches a new execution
     * resuming from the last successful step.
     */
    @PostMapping("/ingest/resume/{jobId}")
    public ResponseEntity<Map<String, Object>> resumeJob(@PathVariable String jobId) {
        try {
            Long executionId = Long.parseLong(jobId);
            BatchIngestionStatus job = batchIngestionService.restartJob(executionId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Job restarted",
                "jobId", job.jobExecutionId().toString()
            ));
        } catch (NumberFormatException e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Invalid job ID format"
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to restart job {}: {}", jobId, e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Get recent ingestion jobs for a connection
     */
    @GetMapping("/ingest/history/{connectionId}")
    public ResponseEntity<List<BatchIngestionStatus>> getJobHistory(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "10") int limit
    ) {
        List<BatchIngestionStatus> jobs = batchIngestionService.getJobHistory(connectionId, limit);
        return ResponseEntity.ok(jobs);
    }
}
