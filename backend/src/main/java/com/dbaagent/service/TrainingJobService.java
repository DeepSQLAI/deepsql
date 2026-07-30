package com.dbaagent.service;

import com.dbaagent.exception.TrainingCancelledException;
import com.dbaagent.model.TableTrainingDetail;
import com.dbaagent.model.TrainingJobHistory;
import com.dbaagent.model.TrainingJobStatus;
import com.dbaagent.model.TrainingRunSummary;
import com.dbaagent.repository.TrainingJobHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrainingJobService {
    private final TrainingService trainingService;
    private final TrainingJobHistoryRepository trainingJobHistoryRepository;
    private final SchemaSnapshotService schemaSnapshotService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Map<String, TrainingJobStatus> jobsByConnection = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Future<?>> futuresByConnection = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByConnection = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${training.schema.max-concurrent:2}")
    private int maxConcurrentJobs;

    @Value("${training.schema.queue-capacity:8}")
    private int queueCapacity;

    private ThreadPoolExecutor executor;

    @PostConstruct
    public void initialize() {
        executor = new ThreadPoolExecutor(
            maxConcurrentJobs,
            maxConcurrentJobs,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public TrainingJobStatus startSchemaTraining(String connectionId) {
        TrainingJobStatus existing = jobsByConnection.get(connectionId);
        if (existing != null &&
            (existing.getStatus() == TrainingJobStatus.Status.RUNNING ||
             existing.getStatus() == TrainingJobStatus.Status.PENDING)) {
            return existing;
        }

        TrainingJobStatus job = TrainingJobStatus.builder()
            .jobId(UUID.randomUUID().toString())
            .connectionId(connectionId)
            .status(TrainingJobStatus.Status.PENDING)
            .message("Queued")
            .progressPercent(0)
            .processedTables(0)
            .build();

        jobsByConnection.put(connectionId, job);
        cancelFlags.put(connectionId, new AtomicBoolean(false));
        persistHistory(job);
        sendStatusUpdate(connectionId, job);

        try {
            Future<?> future = executor.submit(() -> runSchemaTraining(connectionId, job.getJobId()));
            futuresByConnection.put(connectionId, future);
        } catch (RejectedExecutionException rejected) {
            updateJob(connectionId, updated -> {
                updated.setStatus(TrainingJobStatus.Status.REJECTED);
                updated.setCompletedAt(LocalDateTime.now());
                updated.setMessage("Queue full");
                updated.setError("Training queue is full. Try again shortly.");
            });
            TrainingJobStatus rejectedJob = jobsByConnection.get(connectionId);
            persistHistory(rejectedJob);
            recordFinalMetrics(rejectedJob);
        }

        return job;
    }

    public TrainingJobStatus getSchemaTrainingStatus(String connectionId) {
        TrainingJobStatus status = jobsByConnection.get(connectionId);
        if (status != null) {
            return status;
        }

        TrainingJobHistory last = trainingJobHistoryRepository
            .findTopByConnectionIdOrderByCreatedAtDesc(connectionId)
            .orElse(null);

        if (last != null) {
            TrainingJobStatus.Status lastStatus = last.getStatus();
            if (lastStatus == TrainingJobStatus.Status.PENDING ||
                lastStatus == TrainingJobStatus.Status.RUNNING) {
                return TrainingJobStatus.builder()
                    .connectionId(connectionId)
                    .jobId(last.getJobId())
                    .status(TrainingJobStatus.Status.FAILED)
                    .message("Interrupted")
                    .error("Last training did not complete")
                    .totalTables(last.getTotalTables())
                    .processedTables(last.getProcessedTables())
                    .progressPercent(last.getProgressPercent())
                    .startedAt(last.getStartedAt() != null ? last.getStartedAt() : last.getCreatedAt())
                    .completedAt(last.getCompletedAt())
                    .build();
            }

            return TrainingJobStatus.builder()
                .connectionId(connectionId)
                .jobId(last.getJobId())
                .status(last.getStatus())
                .message(last.getMessage())
                .error(last.getError())
                .totalTables(last.getTotalTables())
                .processedTables(last.getProcessedTables())
                .progressPercent(last.getProgressPercent())
                .startedAt(last.getStartedAt() != null ? last.getStartedAt() : last.getCreatedAt())
                .completedAt(last.getCompletedAt())
                .build();
        }

        return TrainingJobStatus.builder()
            .connectionId(connectionId)
            .status(TrainingJobStatus.Status.IDLE)
            .message("Not started")
            .progressPercent(0)
            .processedTables(0)
            .build();
    }

    public TrainingJobStatus cancelSchemaTraining(String connectionId) {
        TrainingJobStatus status = jobsByConnection.get(connectionId);
        if (status == null) {
            return TrainingJobStatus.builder()
                .connectionId(connectionId)
                .status(TrainingJobStatus.Status.IDLE)
                .message("Not started")
                .build();
        }

        if (status.getStatus() != TrainingJobStatus.Status.RUNNING &&
            status.getStatus() != TrainingJobStatus.Status.PENDING) {
            return status;
        }

        cancelFlags.computeIfAbsent(connectionId, id -> new AtomicBoolean(false)).set(true);
        updateJob(connectionId, job -> job.setMessage("Cancellation requested"));

        Future<?> future = futuresByConnection.get(connectionId);
        boolean cancelled = false;
        if (future != null) {
            cancelled = future.cancel(true);
        }

        if (status.getStatus() == TrainingJobStatus.Status.PENDING || cancelled) {
            updateJob(connectionId, job -> {
                job.setStatus(TrainingJobStatus.Status.CANCELLED);
                job.setCompletedAt(LocalDateTime.now());
                job.setMessage("Cancelled");
                job.setError(null);
            });
            TrainingJobStatus cancelledJob = jobsByConnection.get(connectionId);
            persistHistory(cancelledJob);
            recordFinalMetrics(cancelledJob);
            futuresByConnection.remove(connectionId);
            cancelFlags.remove(connectionId);
        }

        return jobsByConnection.get(connectionId);
    }

    public List<TrainingJobHistory> getTrainingHistory(String connectionId, int limit) {
        int resolvedLimit = Math.max(1, Math.min(limit, 25));
        return trainingJobHistoryRepository.findByConnectionIdOrderByCreatedAtDesc(
            connectionId,
            PageRequest.of(0, resolvedLimit)
        );
    }

    public SseEmitter subscribeSchemaTraining(String connectionId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minute timeout
        emittersByConnection
            .computeIfAbsent(connectionId, key -> new CopyOnWriteArrayList<>())
            .add(emitter);

        emitter.onCompletion(() -> removeEmitter(connectionId, emitter));
        emitter.onTimeout(() -> removeEmitter(connectionId, emitter));
        emitter.onError((ex) -> removeEmitter(connectionId, emitter));

        TrainingJobStatus current = getSchemaTrainingStatus(connectionId);
        sendEmitterUpdate(emitter, current);
        return emitter;
    }

    public Map<String, Object> getQueueMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        int active = executor != null ? executor.getActiveCount() : 0;
        int queued = executor != null ? executor.getQueue().size() : 0;
        long inProgressConnections = jobsByConnection.values().stream()
            .filter(job -> job.getStatus() == TrainingJobStatus.Status.RUNNING
                || job.getStatus() == TrainingJobStatus.Status.PENDING)
            .count();

        metrics.put("activeJobs", active);
        metrics.put("queuedJobs", queued);
        metrics.put("inProgressConnections", inProgressConnections);
        metrics.put("maxConcurrentJobs", maxConcurrentJobs);
        metrics.put("queueCapacity", queueCapacity);
        return metrics;
    }

    private void runSchemaTraining(String connectionId, String jobId) {
        List<TableTrainingDetail> tableDetails = new java.util.ArrayList<>();
        AtomicReference<TrainingRunSummary> summaryRef = new AtomicReference<>();

        updateJob(connectionId, job -> {
            job.setJobId(jobId);
            job.setStatus(TrainingJobStatus.Status.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            job.setMessage("Scanning schema");
            job.setError(null);
        });

        try {
            AtomicBoolean cancelFlag = cancelFlags.getOrDefault(connectionId, new AtomicBoolean(false));
            trainingService.trainWithSchema(connectionId, new TrainingService.TrainingProgressListener() {
                @Override
                public void onStart(int totalTables) {
                    updateJob(connectionId, job -> {
                        job.setTotalTables(totalTables);
                        job.setProcessedTables(0);
                        job.setMessage("Preparing embeddings");
                    });
                }

                @Override
                public void onProgress(int processedTables, int totalTables, String tableName) {
                    updateJob(connectionId, job -> {
                        job.setTotalTables(totalTables);
                        job.setProcessedTables(processedTables);
                        job.setCurrentTable(tableName);
                        job.setMessage("Processing tables");
                    });
                }

                @Override
                public void onComplete() {
                    updateJob(connectionId, job -> job.setMessage("Finalizing"));
                }

                @Override
                public void onError(String message) {
                    updateJob(connectionId, job -> job.setError(message));
                }

                @Override
                public void onTableComplete(TableTrainingDetail detail) {
                    if (detail != null) {
                        tableDetails.add(detail);
                    }
                }

                @Override
                public void onRunSummary(TrainingRunSummary summary) {
                    summaryRef.set(summary);
                }

                @Override
                public boolean isCancelled() {
                    return cancelFlag.get() || Thread.currentThread().isInterrupted();
                }

                @Override
                public void onCancelled(String message) {
                    updateJob(connectionId, job -> job.setMessage(message));
                }
            });

            updateJob(connectionId, job -> {
                job.setStatus(TrainingJobStatus.Status.COMPLETED);
                job.setCompletedAt(LocalDateTime.now());
                job.setProgressPercent(100);
                job.setMessage("Completed");
            });
            TrainingJobStatus completedJob = jobsByConnection.get(connectionId);
            persistHistory(completedJob, summaryRef.get(), tableDetails);
            try {
                schemaSnapshotService.captureSnapshot(connectionId);
            } catch (Exception e) {
                log.warn("Failed to capture schema snapshot for connection {}", connectionId, e);
            }
            recordFinalMetrics(completedJob);
        } catch (TrainingCancelledException cancelled) {
            updateJob(connectionId, job -> {
                job.setStatus(TrainingJobStatus.Status.CANCELLED);
                job.setCompletedAt(LocalDateTime.now());
                job.setMessage("Cancelled");
                job.setError(null);
            });
            TrainingJobStatus cancelledJob = jobsByConnection.get(connectionId);
            persistHistory(cancelledJob, summaryRef.get(), tableDetails);
            recordFinalMetrics(cancelledJob);
        } catch (Exception e) {
            log.error("Schema training failed for connection {}", connectionId, e);
            updateJob(connectionId, job -> {
                job.setStatus(TrainingJobStatus.Status.FAILED);
                job.setCompletedAt(LocalDateTime.now());
                job.setMessage("Failed");
                job.setError(e.getMessage());
            });
            TrainingJobStatus failedJob = jobsByConnection.get(connectionId);
            persistHistory(failedJob, summaryRef.get(), tableDetails);
            recordFinalMetrics(failedJob);
        } finally {
            futuresByConnection.remove(connectionId);
            cancelFlags.remove(connectionId);
        }
    }

    private void updateJob(String connectionId, java.util.function.Consumer<TrainingJobStatus> updater) {
        jobsByConnection.compute(connectionId, (key, existing) -> {
            TrainingJobStatus job = existing != null ? existing : TrainingJobStatus.builder()
                .connectionId(connectionId)
                .status(TrainingJobStatus.Status.RUNNING)
                .build();
            updater.accept(job);
            updateProgress(job);
            sendStatusUpdate(connectionId, job);
            return job;
        });
    }

    private void updateProgress(TrainingJobStatus job) {
        Integer total = job.getTotalTables();
        Integer processed = job.getProcessedTables();
        if (total == null || total <= 0 || processed == null) {
            return;
        }
        int percent = Math.min(100, (int) Math.round((processed * 100.0) / total));
        job.setProgressPercent(percent);
    }

    private void persistHistory(TrainingJobStatus job) {
        persistHistory(job, null, null);
    }

    private void persistHistory(
        TrainingJobStatus job,
        TrainingRunSummary summary,
        List<TableTrainingDetail> tableDetails
    ) {
        if (job == null || job.getJobId() == null) {
            return;
        }

        TrainingJobHistory history = trainingJobHistoryRepository.findById(job.getJobId())
            .orElseGet(() -> TrainingJobHistory.builder()
                .jobId(job.getJobId())
                .connectionId(job.getConnectionId())
                .jobType(TrainingJobHistory.JobType.SCHEMA)
                .status(job.getStatus())
                .build());

        history.setStatus(job.getStatus());
        history.setMessage(job.getMessage());
        history.setError(job.getError());
        history.setTotalTables(job.getTotalTables());
        history.setProcessedTables(job.getProcessedTables());
        history.setProgressPercent(job.getProgressPercent());
        history.setStartedAt(job.getStartedAt());
        history.setCompletedAt(job.getCompletedAt());

        if (summary != null) {
            history.setEmbeddingCount(summary.getEmbeddingCount());
            history.setAzureDocumentCount(summary.getAzureDocumentCount());
            history.setEmbeddingDimensions(summary.getEmbeddingDimensions());
        } else if (tableDetails != null && !tableDetails.isEmpty()) {
            history.setEmbeddingCount(tableDetails.size());
            Integer dimensions = tableDetails.get(0).getEmbeddingDimensions();
            history.setEmbeddingDimensions(dimensions != null && dimensions > 0 ? dimensions : null);
        }

        if (tableDetails != null && !tableDetails.isEmpty()) {
            try {
                history.setTableTimings(objectMapper.writeValueAsString(tableDetails));
            } catch (Exception e) {
                log.warn("Failed to serialize training table details for job {}", job.getJobId(), e);
            }
        }

        trainingJobHistoryRepository.save(history);
    }

    private void recordFinalMetrics(TrainingJobStatus job) {
        if (job == null || job.getStatus() == null) {
            return;
        }

        meterRegistry.counter("training.schema.jobs", "status", job.getStatus().name().toLowerCase())
            .increment();

        if (job.getStartedAt() != null && job.getCompletedAt() != null) {
            Duration duration = Duration.between(job.getStartedAt(), job.getCompletedAt());
            meterRegistry.timer("training.schema.duration").record(duration);
        }
    }

    private void sendStatusUpdate(String connectionId, TrainingJobStatus status) {
        List<SseEmitter> emitters = emittersByConnection.get(connectionId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            sendEmitterUpdate(emitter, status);
        }
    }

    private void sendEmitterUpdate(SseEmitter emitter, TrainingJobStatus status) {
        try {
            emitter.send(SseEmitter.event()
                .name("trainingStatus")
                .data(status));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void removeEmitter(String connectionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByConnection.get(connectionId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByConnection.remove(connectionId);
            }
        }
    }

    // --- Init progress SSE infrastructure ---

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> initEmittersByConnection =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Synchronous schema training for use in ConnectionInitPipeline.
     * Unlike startSchemaTraining(), this runs on the caller's thread and
     * supports a progress listener for pipeline stage tracking.
     */
    public void startSchemaTrainingSync(String connectionId, TrainingService.TrainingProgressListener listener) {
        trainingService.trainWithSchema(connectionId, listener);
    }

    public SseEmitter subscribeInitProgress(String connectionId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minute timeout
        initEmittersByConnection
            .computeIfAbsent(connectionId, key -> new CopyOnWriteArrayList<>())
            .add(emitter);
        emitter.onCompletion(() -> removeInitEmitter(connectionId, emitter));
        emitter.onTimeout(() -> removeInitEmitter(connectionId, emitter));
        emitter.onError((ex) -> removeInitEmitter(connectionId, emitter));
        return emitter;
    }

    public void broadcastInitProgress(String connectionId, com.dbaagent.model.ConnectionInitStatus status) {
        var emitters = initEmittersByConnection.get(connectionId);
        if (emitters == null) return;
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(status).build());
                if (status.getCurrentStage() == com.dbaagent.model.InitStage.COMPLETED
                        || status.getCurrentStage() == com.dbaagent.model.InitStage.FAILED) {
                    emitter.complete();
                }
            } catch (Exception e) {
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        }
        if (status.getCurrentStage() == com.dbaagent.model.InitStage.COMPLETED
                || status.getCurrentStage() == com.dbaagent.model.InitStage.FAILED) {
            initEmittersByConnection.remove(connectionId);
        }
    }

    private void removeInitEmitter(String connectionId, SseEmitter emitter) {
        var emitters = initEmittersByConnection.get(connectionId);
        if (emitters != null) emitters.remove(emitter);
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
