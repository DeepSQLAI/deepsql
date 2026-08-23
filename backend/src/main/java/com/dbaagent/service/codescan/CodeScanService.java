package com.dbaagent.service.codescan;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import com.dbaagent.model.code.CodeScanJob;
import com.dbaagent.model.code.CodeScanSource;
import com.dbaagent.model.code.CodeScanFileHash;
import com.dbaagent.repository.CodeKnowledgeSuggestionRepository;
import com.dbaagent.repository.CodeScanFileHashRepository;
import com.dbaagent.repository.CodeScanJobRepository;
import com.dbaagent.repository.CodeScanSourceRepository;
import com.dbaagent.service.SchemaScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

/**
 * Orchestrates the end-to-end code scan: upload → extract archive → walk + chunk →
 * LLM extract → aggregate → persist as PENDING suggestions, with live progress
 * reported through SSE.
 *
 * Mirrors the {@code TrainingJobService} pattern (executor + per-connection
 * emitters), so the runtime characteristics are familiar to the rest of the
 * codebase.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeScanService {

    private final CodeScanSourceRepository sourceRepository;
    private final CodeScanJobRepository jobRepository;
    private final CodeKnowledgeSuggestionRepository suggestionRepository;
    private final CodeScanFileHashRepository fileHashRepository;
    private final CodeArchiveExtractor archiveExtractor;
    private final CodeFileScanner fileScanner;
    private final CodeKnowledgeExtractor knowledgeExtractor;
    private final CodeSuggestionAggregator aggregator;
    private final CodeSuggestionApplier applier;
    private final SchemaScannerService schemaScannerService;
    private final SchemaAmbiguityService schemaAmbiguityService;

    @Value("${code-scan.executor.core:2}")
    private int executorCore;
    @Value("${code-scan.executor.max:4}")
    private int executorMax;
    @Value("${code-scan.max-chunks-per-job:400}")
    private int maxChunksPerJob;
    @Value("${code-scan.max-parallel-chunks:8}")
    private int maxParallelChunks;

    private ExecutorService executor;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByJob = new ConcurrentHashMap<>();

    private synchronized ExecutorService executor() {
        if (executor == null) {
            executor = new ThreadPoolExecutor(
                Math.max(1, executorCore),
                Math.max(executorCore, executorMax),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                Executors.defaultThreadFactory()
            );
        }
        return executor;
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    // ---- Source management ----

    @Transactional
    public CodeScanSource createSourceFromUpload(String connectionId,
                                                 MultipartFile file,
                                                 String name,
                                                 String focusText,
                                                 String createdBy) throws IOException {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("upload is required");
        }
        // We extract once here just to get sha + size; the active job re-extracts so
        // we don't have to keep a temp dir alive between requests.
        CodeArchiveExtractor.Result probe = archiveExtractor.extract(file);
        try {
            String trimmedFocus = focusText == null ? null : focusText.trim();
            String hash = computeFocusHash(connectionId, trimmedFocus);
            CodeScanSource source = CodeScanSource.builder()
                .connectionId(connectionId)
                .name((name == null || name.isBlank()) ? defaultName(file) : name)
                .kind(CodeScanSource.Kind.UPLOAD)
                .archiveSha256(probe.archiveSha256())
                .totalBytes(probe.totalBytes())
                .fileCount(probe.fileCount())
                .createdBy(createdBy)
                .active(Boolean.TRUE)
                .focusText(trimmedFocus)
                .focusHash(hash)
                .focusUpdatedAt(trimmedFocus != null && !trimmedFocus.isBlank() ? LocalDateTime.now() : null)
                .build();
            return sourceRepository.save(source);
        } finally {
            deleteRecursively(probe.root());
        }
    }

    @Transactional
    public CodeScanSource updateFocus(String sourceId, String focusText) {
        CodeScanSource source = sourceRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("Source not found: " + sourceId));
        String trimmed = focusText == null ? null : focusText.trim();
        source.setFocusText(trimmed);
        source.setFocusHash(computeFocusHash(source.getConnectionId(), trimmed));
        source.setFocusUpdatedAt(LocalDateTime.now());
        return sourceRepository.save(source);
    }

    /**
     * Hash represents (userFocus + ambiguity-derived focus) so two sources
     * with identical inputs collapse and so the UI can detect "the source's
     * focus changed since its last successful job".
     */
    private String computeFocusHash(String connectionId, String userFocus) {
        try {
            String ambiguity = schemaAmbiguityService.summariseForFocus(connectionId);
            String combined = (userFocus == null ? "" : userFocus) + "\n" + (ambiguity == null ? "" : ambiguity);
            if (combined.isBlank()) return null;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.debug("focus hash compute failed: {}", e.getMessage());
            return null;
        }
    }

    public List<CodeScanSource> listSources(String connectionId) {
        return sourceRepository.findByConnectionIdAndActiveTrueOrderByCreatedAtDesc(connectionId);
    }

    @Transactional
    public void deleteSource(String sourceId) {
        sourceRepository.findById(sourceId).ifPresent(s -> {
            s.setActive(false);
            sourceRepository.save(s);
        });
        // Clear cached file hashes — re-activating the source later (or
        // creating a different one with the same id, which won't happen but
        // belt-and-braces) shouldn't reuse stale dedup state.
        try {
            fileHashRepository.deleteBySourceId(sourceId);
        } catch (Exception e) {
            log.debug("file hash cleanup on source delete failed: {}", e.getMessage());
        }
    }

    // ---- Job management ----

    /**
     * Starts a scan against the given source. The supplied {@link MultipartFile}
     * is the archive to scan. We require it on each scan so we never need to
     * persist the source archive on disk between requests.
     */
    @Transactional
    public CodeScanJob startScan(String sourceId,
                                 MultipartFile archive,
                                 String triggeredBy) throws IOException {
        return startScan(sourceId, archive, null, triggeredBy);
    }

    @Transactional
    public CodeScanJob startScan(String sourceId,
                                 MultipartFile archive,
                                 String focusOverride,
                                 String triggeredBy) throws IOException {
        CodeScanSource source = sourceRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("Source not found: " + sourceId));
        if (!Boolean.TRUE.equals(source.getActive())) {
            throw new IllegalStateException("Source has been deleted");
        }
        if (archive == null || archive.isEmpty()) {
            throw new IllegalArgumentException("archive upload is required to start a scan");
        }

        // Persist override (if any) onto the source before computing effective focus.
        if (focusOverride != null) {
            source.setFocusText(focusOverride.trim());
            source.setFocusHash(computeFocusHash(source.getConnectionId(), focusOverride.trim()));
            source.setFocusUpdatedAt(LocalDateTime.now());
            source = sourceRepository.save(source);
        }

        // Compose effective focus (user + ambiguity) once at scan-start so
        // every chunk in this run sees the same prior.
        String userFocus = source.getFocusText();
        String ambiguityFocus = "";
        try {
            ambiguityFocus = schemaAmbiguityService.summariseForFocus(source.getConnectionId());
        } catch (Exception e) {
            log.warn("ambiguity focus compute failed: {}", e.getMessage());
        }
        String effectiveFocus = renderEffectiveFocus(userFocus, ambiguityFocus);
        String effectiveFocusHash = computeFocusHash(source.getConnectionId(), userFocus);

        CodeScanJob job = CodeScanJob.builder()
            .sourceId(sourceId)
            .connectionId(source.getConnectionId())
            .status(CodeScanJob.Status.PENDING)
            .progress(0)
            .currentStep("queued")
            .triggeredBy(triggeredBy)
            .focusText(effectiveFocus)
            .focusHash(effectiveFocusHash)
            .build();
        job = jobRepository.save(job);

        final String jobId = job.getId();
        final String connectionId = source.getConnectionId();
        final String capturedFocus = effectiveFocus;

        // Capture the upload to a temp dir up-front; the request thread holds
        // the multipart, but the worker runs after we return.
        CodeArchiveExtractor.Result extracted = archiveExtractor.extract(archive);

        // Defer the worker submission until AFTER the surrounding @Transactional
        // commits — otherwise the worker's first findById can race the commit
        // and miss the row, leaving status stuck at PENDING.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor().submit(() -> runJob(jobId, connectionId, extracted.root(), sourceId, capturedFocus));
                }
            });
        } else {
            executor().submit(() -> runJob(jobId, connectionId, extracted.root(), sourceId, capturedFocus));
        }
        return job;
    }

    /**
     * After a new run's suggestions land, mark any prior PENDING suggestion
     * from earlier jobs of the same source that targeted the same object as
     * SUPERSEDED. We only touch PENDING — APPROVED / REJECTED / already
     * SUPERSEDED rows are preserved.
     */
    private void supersedeStalePending(String connectionId,
                                       String sourceId,
                                       String newJobId,
                                       List<CodeKnowledgeSuggestion> newBatch) {
        java.util.Set<String> newTargetKeys = new java.util.HashSet<>();
        for (CodeKnowledgeSuggestion s : newBatch) {
            newTargetKeys.add(targetKey(s.getTargetKind(), s.getTargetObject()));
        }
        // Find prior jobs for this source.
        List<CodeScanJob> priorJobs = jobRepository.findBySourceIdOrderByCreatedAtDesc(sourceId);
        java.util.Set<String> priorJobIds = new java.util.HashSet<>();
        for (CodeScanJob j : priorJobs) {
            if (!java.util.Objects.equals(j.getId(), newJobId)) {
                priorJobIds.add(j.getId());
            }
        }
        if (priorJobIds.isEmpty()) return;

        int superseded = 0;
        for (CodeKnowledgeSuggestion existing : suggestionRepository
                .findByConnectionIdAndStatusOrderByConfidenceDesc(
                    connectionId, CodeKnowledgeSuggestion.Status.PENDING)) {
            if (!priorJobIds.contains(existing.getJobId())) continue;
            String key = targetKey(existing.getTargetKind(), existing.getTargetObject());
            if (!newTargetKeys.contains(key)) continue;
            existing.setStatus(CodeKnowledgeSuggestion.Status.SUPERSEDED);
            existing.setDecisionNote("superseded by job " + newJobId);
            existing.setDecidedAt(LocalDateTime.now());
            suggestionRepository.save(existing);
            superseded++;
        }
        if (superseded > 0) {
            log.info("supersession: marked {} prior PENDING suggestions SUPERSEDED for source {}",
                superseded, sourceId);
        }
    }

    private static String targetKey(CodeKnowledgeSuggestion.TargetKind kind, String target) {
        return (kind == null ? "_" : kind.name()) + "|" + (target == null ? "_" : target.toLowerCase(java.util.Locale.ROOT));
    }

    /** Returns previously-recorded (path → sha256) for this source. */
    private Map<String, String> loadPriorFileHashes(String sourceId) {
        var rows = fileHashRepository.findBySourceId(sourceId);
        if (rows.isEmpty()) return java.util.Collections.emptyMap();
        Map<String, String> out = new HashMap<>(rows.size());
        for (CodeScanFileHash row : rows) {
            out.put(row.getRelativePath(), row.getSha256());
        }
        return out;
    }

    /** Focus hash of the most recent successful job for this source, excluding the current one. */
    private String lastSuccessfulFocusHash(String sourceId, String currentJobId) {
        for (CodeScanJob j : jobRepository.findBySourceIdOrderByCreatedAtDesc(sourceId)) {
            if (java.util.Objects.equals(j.getId(), currentJobId)) continue;
            if (j.getStatus() == CodeScanJob.Status.COMPLETED) {
                return j.getFocusHash();
            }
        }
        return null;
    }

    /**
     * Upsert the file-hash table for every file we saw in this scan. We store
     * even unchanged files so the next scan recognises them as unchanged again.
     * Files that disappeared from the archive remain in the table with their
     * stale hash — harmless (they'll never match a future scan's chunk).
     */
    private void persistFileHashes(String sourceId, String jobId, List<CodeFileScanner.CodeChunk> chunks) {
        // One hash per unique file path (chunks within a file share a fileSha256).
        Map<String, String> byPath = new HashMap<>();
        for (var c : chunks) {
            byPath.put(c.path(), c.fileSha256());
        }
        if (byPath.isEmpty()) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            List<CodeScanFileHash> rows = new ArrayList<>(byPath.size());
            for (var e : byPath.entrySet()) {
                rows.add(CodeScanFileHash.builder()
                    .sourceId(sourceId)
                    .relativePath(e.getKey())
                    .sha256(e.getValue())
                    .lastJobId(jobId)
                    .lastScannedAt(now)
                    .build());
            }
            fileHashRepository.saveAll(rows);
        } catch (Exception e) {
            log.warn("file hash persist failed for source {}: {}", sourceId, e.getMessage());
        }
    }

    private static String renderEffectiveFocus(String userFocus, String ambiguityFocus) {
        boolean hasUser = userFocus != null && !userFocus.isBlank();
        boolean hasAmb = ambiguityFocus != null && !ambiguityFocus.isBlank();
        if (!hasUser && !hasAmb) return "";
        StringBuilder sb = new StringBuilder();
        if (hasUser) {
            sb.append("User-supplied focus:\n").append(userFocus.trim()).append("\n\n");
        }
        if (hasAmb) {
            sb.append(ambiguityFocus.trim());
        }
        // Cap to ~3 KB; keep user portion intact, truncate ambiguity tail.
        if (sb.length() > 3000) {
            return sb.substring(0, 3000) + "\n…(truncated)";
        }
        return sb.toString();
    }

    private void runJob(String jobId, String connectionId, Path workdir, String sourceId, String effectiveFocus) {
        try {
            updateJob(jobId, j -> {
                j.setStatus(CodeScanJob.Status.RUNNING);
                j.setStartedAt(LocalDateTime.now());
                j.setCurrentStep("scanning files");
                j.setProgress(5);
            });

            List<CodeFileScanner.CodeChunk> chunks = fileScanner.scan(workdir, effectiveFocus);

            // Per-file dedup: skip files whose SHA matches the last successful
            // scan, but ONLY when this scan's focus matches the prior job's
            // focus. If focus changed, the LLM prompt is different, so unchanged
            // code may still produce different suggestions — re-run everything.
            Map<String, String> priorHashes = loadPriorFileHashes(sourceId);
            String priorJobFocusHash = lastSuccessfulFocusHash(sourceId, jobId);
            String currentJobFocusHash = jobRepository.findById(jobId)
                .map(CodeScanJob::getFocusHash).orElse(null);
            boolean focusChanged = !java.util.Objects.equals(priorJobFocusHash, currentJobFocusHash);

            List<CodeFileScanner.CodeChunk> emittedChunks;
            int skippedChunks = 0;
            if (priorHashes.isEmpty() || focusChanged) {
                emittedChunks = chunks;
                if (focusChanged && !priorHashes.isEmpty()) {
                    log.info("focus changed since last scan — re-LLMing all chunks "
                        + "(prior focus_hash={}, current focus_hash={})",
                        priorJobFocusHash, currentJobFocusHash);
                }
            } else {
                emittedChunks = new ArrayList<>(chunks.size());
                for (var c : chunks) {
                    String prior = priorHashes.get(c.path());
                    if (prior != null && prior.equals(c.fileSha256())) {
                        skippedChunks++;
                    } else {
                        emittedChunks.add(c);
                    }
                }
                log.info("file-hash dedup: kept {} chunks, skipped {} unchanged",
                    emittedChunks.size(), skippedChunks);
            }

            int chunkCount = Math.min(emittedChunks.size(), maxChunksPerJob);
            updateJob(jobId, j -> {
                j.setFilesTotal(chunkCount);
                j.setCurrentStep("loading schema");
                j.setProgress(10);
            });

            SchemaMetadata schema;
            try {
                schema = schemaScannerService.scanSchema(connectionId);
            } catch (Exception e) {
                throw new RuntimeException("Schema scan failed: " + e.getMessage(), e);
            }

            updateJob(jobId, j -> {
                j.setCurrentStep("extracting knowledge");
                j.setProgress(15);
            });

            // Cap, then parallelize LLM extraction. Each chunk is one Azure
            // OpenAI call; serial execution would take ~80 min for 400 chunks.
            List<CodeFileScanner.CodeChunk> bounded = emittedChunks.size() > maxChunksPerJob
                ? emittedChunks.subList(0, maxChunksPerJob) : emittedChunks;
            List<CodeKnowledgeExtractor.RawSuggestion> raw =
                java.util.Collections.synchronizedList(new ArrayList<>());
            java.util.concurrent.atomic.AtomicInteger processedRef =
                new java.util.concurrent.atomic.AtomicInteger();

            try (var llmPool = java.util.concurrent.Executors.newFixedThreadPool(maxParallelChunks)) {
                List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
                for (var chunk : bounded) {
                    futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        var emitted = knowledgeExtractor.extractFromChunk(chunk, schema, effectiveFocus);
                        raw.addAll(emitted);
                        int p = processedRef.incrementAndGet();
                        if (p % 10 == 0 || p == bounded.size()) {
                            int rawSize = raw.size();
                            updateJob(jobId, j -> {
                                j.setFilesParsed(p);
                                j.setChunksSent(p);
                                j.setSuggestionsEmitted(rawSize);
                                j.setProgress(15 + (int) Math.round(70.0 * p / Math.max(1, bounded.size())));
                            });
                        }
                    }, llmPool));
                }
                java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .join();
            }

            updateJob(jobId, j -> {
                j.setCurrentStep("aggregating");
                j.setProgress(90);
            });

            List<CodeKnowledgeSuggestion> aggregated = aggregator.aggregate(connectionId, jobId, raw, schema);
            if (!aggregated.isEmpty()) {
                suggestionRepository.saveAll(aggregated);
                supersedeStalePending(connectionId, sourceId, jobId, aggregated);
            }

            updateJob(jobId, j -> {
                j.setStatus(CodeScanJob.Status.COMPLETED);
                j.setCurrentStep("done");
                j.setProgress(100);
                j.setCompletedAt(LocalDateTime.now());
                j.setSuggestionsEmitted(aggregated.size());
                j.setMessage("Generated " + aggregated.size() + " suggestions for review");
            });

            sourceRepository.findById(sourceId).ifPresent(s -> {
                s.setLastScannedAt(LocalDateTime.now());
                sourceRepository.save(s);
            });
            // Persist current file hashes for ALL chunks we saw (not just the
            // ones we sent to the LLM) so the next scan's dedup can recognise
            // unchanged files even when this run skipped them.
            persistFileHashes(sourceId, jobId, chunks);
        } catch (Exception e) {
            log.error("code scan job {} failed", jobId, e);
            updateJob(jobId, j -> {
                j.setStatus(CodeScanJob.Status.FAILED);
                j.setCompletedAt(LocalDateTime.now());
                j.setMessage(truncate(e.getMessage(), 800));
            });
        } finally {
            deleteRecursively(workdir);
            closeEmitters(jobId);
        }
    }

    public Optional<CodeScanJob> getJob(String jobId) {
        return jobRepository.findById(jobId);
    }

    public List<CodeScanJob> recentJobs(String sourceId) {
        return jobRepository.findBySourceIdOrderByCreatedAtDesc(sourceId);
    }

    public SseEmitter subscribeJob(String jobId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — we'll close on terminal state
        emittersByJob.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        // Send initial snapshot so UI doesn't have to make a separate GET.
        jobRepository.findById(jobId).ifPresent(j -> sendSafe(emitter, j));
        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(t -> removeEmitter(jobId, emitter));
        return emitter;
    }

    // ---- Suggestion review ----

    public Page<CodeKnowledgeSuggestion> listSuggestions(String connectionId,
                                                         CodeKnowledgeSuggestion.Status status,
                                                         int page,
                                                         int size) {
        return suggestionRepository.findByConnectionIdAndStatus(
            connectionId, status, PageRequest.of(page, size, sortFor(status)));
    }

    /**
     * Sort depends on what the reviewer is looking at.
     *
     * <p>PENDING is a work queue — highest confidence first, so the best candidates
     * are the ones you see. Anything already decided is a history view, and the
     * question there is "what did I just do", so it leads with the most recent
     * decision. Sorting decided rows by confidence scattered a fresh approval
     * somewhere in the middle of hundreds of older ones.
     *
     * <p>{@code decidedAt} is null on rows decided before it was recorded, hence
     * nullsLast with a createdAt fallback.
     */
    private static Sort sortFor(CodeKnowledgeSuggestion.Status status) {
        if (status == CodeKnowledgeSuggestion.Status.PENDING) {
            return Sort.by(Sort.Direction.DESC, "confidence", "createdAt");
        }
        return Sort.by(
            new Sort.Order(Sort.Direction.DESC, "decidedAt", Sort.NullHandling.NULLS_LAST),
            new Sort.Order(Sort.Direction.DESC, "createdAt")
        );
    }

    /**
     * Routes to applier.approve / applier.reject. NOT @Transactional itself —
     * each applier call carries its own transaction so a failure on one item
     * does not poison the surrounding context.
     */
    public CodeKnowledgeSuggestion decide(String suggestionId,
                                          String decision,
                                          String decidedBy,
                                          String note) {
        if ("APPROVED".equalsIgnoreCase(decision)) {
            return applier.approve(suggestionId, decidedBy, note);
        } else if ("REJECTED".equalsIgnoreCase(decision)) {
            return applier.reject(suggestionId, decidedBy, note);
        }
        throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
    }

    /**
     * Bulk-decide is intentionally NOT @Transactional. Each item is its own
     * transaction inside applier.approve/reject, so one bad row cannot mark a
     * shared transaction rollback-only and break every subsequent item.
     */
    public BulkDecideResult bulkDecide(List<String> ids,
                                       String decision,
                                       String decidedBy,
                                       String note) {
        List<CodeKnowledgeSuggestion> out = new ArrayList<>();
        List<Map<String, String>> failures = new ArrayList<>();
        for (String id : ids) {
            try {
                out.add(decide(id, decision, decidedBy, note));
            } catch (Exception e) {
                String message = rootMessage(e);
                failures.add(Map.of("id", id, "error", message));
                log.warn("bulk decide skipped {}: {}", id, message);
            }
        }
        if (!failures.isEmpty()) {
            log.info("bulk decide: {} succeeded, {} failed", out.size(), failures.size());
        }
        return new BulkDecideResult(out, failures);
    }

    public record BulkDecideResult(
        List<CodeKnowledgeSuggestion> succeeded,
        List<Map<String, String>> failures
    ) {}

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        String best = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                best = cur.getMessage();
            }
            cur = cur.getCause();
        }
        // Keep API payloads short — full stack stays in logs.
        if (best.length() > 400) {
            return best.substring(0, 397) + "...";
        }
        return best;
    }

    // ---- Helpers ----

    private void updateJob(String jobId, java.util.function.Consumer<CodeScanJob> mutator) {
        jobRepository.findById(jobId).ifPresent(j -> {
            mutator.accept(j);
            CodeScanJob saved = jobRepository.save(j);
            broadcast(jobId, saved);
        });
    }

    private void broadcast(String jobId, CodeScanJob job) {
        var emitters = emittersByJob.get(jobId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            sendSafe(emitter, job);
        }
        if (job.getStatus() == CodeScanJob.Status.COMPLETED
            || job.getStatus() == CodeScanJob.Status.FAILED
            || job.getStatus() == CodeScanJob.Status.CANCELLED) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignore) { /* already closed */ }
            }
            emittersByJob.remove(jobId);
        }
    }

    private void sendSafe(SseEmitter emitter, CodeScanJob job) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("jobId", job.getId());
            payload.put("status", job.getStatus().name());
            payload.put("progress", job.getProgress());
            payload.put("currentStep", job.getCurrentStep());
            payload.put("filesParsed", job.getFilesParsed());
            payload.put("filesTotal", job.getFilesTotal());
            payload.put("suggestionsEmitted", job.getSuggestionsEmitted());
            payload.put("message", job.getMessage());
            emitter.send(SseEmitter.event().name("progress").data(payload));
        } catch (Exception e) {
            removeEmitter(job.getId(), emitter);
        }
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        var emitters = emittersByJob.get(jobId);
        if (emitters != null) emitters.remove(emitter);
    }

    private void closeEmitters(String jobId) {
        var emitters = emittersByJob.remove(jobId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            try { emitter.complete(); } catch (Exception ignore) { /* */ }
        }
    }

    private static String defaultName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return (name == null || name.isBlank()) ? "code-source" : name;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void deleteRecursively(Path root) {
        if (root == null) return;
        try (var stream = java.nio.file.Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { java.nio.file.Files.deleteIfExists(p); } catch (IOException ignore) { /* */ }
                });
        } catch (IOException ignore) {
            // workdir may already be cleaned
        }
    }
}
