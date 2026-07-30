package com.dbaagent.service.scheduler;

import com.dbaagent.model.ConnectionInitHistory;
import com.dbaagent.model.ConnectionInitStatus;
import com.dbaagent.model.ConnectionInitStatus.StageTimingEntry;
import com.dbaagent.model.InitStage;
import com.dbaagent.model.TrainingRunSummary;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import com.dbaagent.repository.ConnectionInitHistoryRepository;
import com.dbaagent.repository.ConnectionInitStatusRepository;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.SchemaDescriptionService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.SchemaSnapshotService;
import com.dbaagent.service.TrainingJobService;
import com.dbaagent.service.TrainingService;
import com.dbaagent.service.VectorSearchService;
import com.dbaagent.service.brain.analysis.ColumnProfilingService;
import com.dbaagent.service.brain.keycolumn.ColumnValueCollectionService;
import com.dbaagent.service.brain.keycolumn.JoinRelationshipInferenceService;
import com.dbaagent.service.brain.keycolumn.KeyColumnAnalysisService;
import com.dbaagent.service.brain.classification.SchemaClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes a single Brain initialization stage.
 * Each stage is idempotent (all writes are upserts).
 * All status writes are conditional on active_run_id — if a reinit
 * supersedes this run, writes silently stop (no clobbering).
 * Returns the next stage to schedule, or null if done/cancelled/superseded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrainInitStageExecutor {

    private final TrainingJobService trainingJobService;
    private final SchemaDescriptionService schemaDescriptionService;
    private final ColumnProfilingService columnProfilingService;
    private final ConnectionService connectionService;
    private final SchemaScannerService schemaScannerService;
    private final SchemaSnapshotService schemaSnapshotService;
    private final QueryExecutorService queryExecutorService;
    private final KeyColumnAnalysisService keyColumnAnalysisService;
    private final ColumnValueCollectionService columnValueCollectionService;
    private final JoinRelationshipInferenceService joinRelationshipInferenceService;
    private final SchemaClassificationService schemaClassificationService;
    private final SemanticModelService semanticModelService;
    private final VectorSearchService vectorSearchService;
    private final ConnectionInitStatusRepository initStatusRepo;
    private final ConnectionInitHistoryRepository initHistoryRepo;
    private final ColumnProfileRepository columnProfileRepository;
    private final QueryLineageRepository queryLineageRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final SchemaDocumentationRepository schemaDocumentationRepository;
    private final CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;

    /**
     * Execute a single stage of the Brain init pipeline.
     *
     * @param data the task data with connectionId, currentStage, and runId
     * @return the next InitStage to schedule, or null if pipeline is done, cancelled, or superseded
     */
    public InitStage executeStage(BrainInitTaskData data) {
        String connectionId = data.connectionId();
        InitStage stage = data.currentStage();
        UUID taskRunId = UUID.fromString(data.runId());

        var statusOpt = initStatusRepo.findById(connectionId);
        if (statusOpt.isEmpty()) {
            log.warn("No init status found for {} at stage {}, skipping", connectionId, stage);
            return null;
        }
        ConnectionInitStatus status = statusOpt.get();

        if (!taskRunId.equals(status.getActiveRunId())) {
            log.warn("Stale task for {} — task runId={} but active runId={}, skipping",
                connectionId, data.runId(), status.getActiveRunId());
            return null;
        }

        if (Boolean.TRUE.equals(status.getCancelRequested())) {
            log.info("Init cancelled for {} before stage {}", connectionId, stage);
            markFailed(status, "Cancelled by user", taskRunId);
            return null;
        }

        if (!updateStage(status, stage, stageStartPercent(stage),
                "Starting " + stage.name() + "...", taskRunId)) {
            return null;
        }

        try {
            return switch (stage) {
                case SCHEMA_SCAN -> executeSchemaScan(connectionId, status, taskRunId);
                case DATA_SAMPLING -> executeDataSampling(connectionId, status, taskRunId);
                case KEY_COLUMN_ANALYSIS -> executeKeyColumnAnalysis(connectionId, status, taskRunId);
                case COLUMN_VALUE_COLLECTION -> executeColumnValueCollection(connectionId, status, taskRunId);
                case INFERRED_RELATIONSHIPS -> executeInferredRelationships(connectionId, status, taskRunId);
                case SCHEMA_CLASSIFICATION -> executeSchemaClassification(connectionId, status, taskRunId);
                case AI_DESCRIPTION -> executeAiDescription(connectionId, status, taskRunId);
                case RAG_EMBEDDING -> executeRagEmbedding(connectionId, status, taskRunId);
                case BRAIN_ANALYSIS -> executeBrainAnalysis(connectionId, status, taskRunId);
                case SEMANTIC_MODELING -> executeSemanticModeling(connectionId, status, taskRunId);
                case COMPLETED, FAILED -> null;
            };
        } catch (Exception e) {
            if (isStaleOrCancelled(connectionId, taskRunId)) {
                log.info("Run {} superseded or cancelled for {} during stage {}",
                    taskRunId, connectionId, stage);
            } else {
                log.error("Stage {} failed for {}: {}", stage, connectionId, e.getMessage(), e);
                markFailed(status, sanitizeError(e), taskRunId);
            }
            return null;
        }
    }

    // ── Stage implementations ──────────────────────────────────────────

    private InitStage executeSchemaScan(String connectionId, ConnectionInitStatus status, UUID taskRunId) throws Exception {
        updateProgress(status, 5, "Refreshing schema caches and capturing a fresh snapshot...", taskRunId);
        queryExecutorService.evictDatabaseObjectsCache(connectionId);
        schemaScannerService.evictSchemaCache(connectionId);

        var schema = schemaScannerService.scanSchema(connectionId);
        int tablesDiscovered = schema.getTables() != null ? schema.getTables().size() : 0;
        int columnsDiscovered = schema.getTables() != null
            ? schema.getTables().stream().mapToInt(table ->
                table.getColumns() != null ? table.getColumns().size() : 0).sum()
            : 0;
        var snapshot = schemaSnapshotService.captureSnapshot(connectionId, false);

        Map<String, Object> details = new HashMap<>();
        details.put("tablesDiscovered", tablesDiscovered);
        details.put("columnsDiscovered", columnsDiscovered);
        if (snapshot != null && snapshot.getCapturedAt() != null) {
            details.put("snapshotCapturedAt", snapshot.getCapturedAt());
        }
        if (snapshot != null && snapshot.getSchemaHash() != null) {
            details.put("schemaFingerprint", snapshot.getSchemaHash());
        }
        details.put("method", "Evicts cached schema/object metadata, rescans the live schema, and stores a fresh snapshot");
        recordStageDetails(status, InitStage.SCHEMA_SCAN, details);
        updateProgress(status, 18,
            "Scanned " + tablesDiscovered + " tables and captured a fresh schema snapshot", taskRunId);
        return InitStage.DATA_SAMPLING;
    }

    private InitStage executeDataSampling(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        int tablesProfiled = 0;
        int columnsProfiled = 0;
        String profilingStatus = "skipped";
        boolean samplingEnabled = connectionService.isDataSamplingEnabled(connectionId);

        if (samplingEnabled) {
            try {
                var profileResult = columnProfilingService.profileConnection(connectionId,
                    (processed, total, tableName) -> {
                        int pct = 25 + (int) ((processed / (double) Math.max(total, 1)) * 10);
                        updateProgress(status, pct,
                            "Profiling table " + tableName + " (" + processed + "/" + total + ")",
                            taskRunId);
                    });
                tablesProfiled = profileResult.getTablesProfiled();
                columnsProfiled = profileResult.getColumnsProfiled();
                profilingStatus = "completed";
            } catch (Exception e) {
                profilingStatus = "failed";
                log.warn("Column profiling failed for {}, AI will use schema-only descriptions: {}",
                    connectionId, e.getMessage());
            }
        } else {
            log.info("Data sampling disabled for connection {}, skipping column profiling", connectionId);
        }

        recordStageDetails(status, InitStage.DATA_SAMPLING, Map.of(
            "tablesProfiled", tablesProfiled,
            "columnsProfiled", columnsProfiled,
            "profilingStatus", profilingStatus,
            "samplingEnabled", samplingEnabled,
            "profileCount", columnProfileRepository.countByConnectionId(connectionId),
            "profiledAtWatermark", asIsoString(columnProfileRepository.findLatestProfiledAt(connectionId)),
            "method", "Column profiling (distinct counts, null rates, top values, min/max) + SELECT * FROM table LIMIT 5 per table"
        ));
        String profilingMsg = switch (profilingStatus) {
            case "completed" -> "Profiled " + columnsProfiled + " columns across " + tablesProfiled + " tables";
            case "failed" -> "Column profiling failed, continuing with schema-only descriptions";
            default -> "Column profiling skipped (data sampling disabled)";
        };
        updateProgress(status, 30, profilingMsg, taskRunId);
        return InitStage.KEY_COLUMN_ANALYSIS;
    }

    private InitStage executeKeyColumnAnalysis(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        try {
            var result = keyColumnAnalysisService.analyzeKeyColumns(connectionId, false);
            int columnsFound = result != null && result.getTotalColumnsAnalyzed() != null ? result.getTotalColumnsAnalyzed() : 0;
            int antiPatterns = result != null ? result.getAntiPatternsDetected() : 0;
            var latestLineage = queryLineageRepository.findLatestCreatedAt(connectionId);
            var latestSlowQuery = slowQueryHistoryRepository.findLatestCreatedAt(connectionId);
            recordStageDetails(status, InitStage.KEY_COLUMN_ANALYSIS, Map.of(
                "columnsAnalyzed", columnsFound,
                "antiPatternsDetected", antiPatterns,
                "queryLineageWatermark", asIsoString(latestLineage),
                "slowQueryWatermark", asIsoString(latestSlowQuery),
                "queryEvidenceWatermark", asIsoString(latest(latestLineage, latestSlowQuery)),
                "method", "Identifies important columns from slow query patterns, JOIN usage, WHERE clauses, and grouping patterns"
            ));
            updateProgress(status, 40, "Analyzed " + columnsFound + " key columns", taskRunId);
        } catch (Exception e) {
            log.warn("Key column analysis failed for {} (non-fatal, continuing): {}", connectionId, e.getMessage());
            recordStageDetails(status, InitStage.KEY_COLUMN_ANALYSIS, Map.of(
                "status", "failed",
                "error", sanitizeError(e),
                "method", "Identifies important columns from slow query patterns, JOIN usage, WHERE clauses, and grouping patterns"
            ));
            updateProgress(status, 40, "Key column analysis skipped (no query evidence yet)", taskRunId);
        }
        return InitStage.COLUMN_VALUE_COLLECTION;
    }

    private InitStage executeColumnValueCollection(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        try {
            var summary = columnValueCollectionService.analyzeColumnValues(connectionId, null);
            recordStageDetails(status, InitStage.COLUMN_VALUE_COLLECTION, Map.of(
                "candidateColumns", summary.candidateColumns(),
                "processedColumns", summary.processedColumns(),
                "cachedColumns", summary.cachedColumns(),
                "lowCardinalityColumns", summary.lowCardinalityColumns(),
                "embeddedColumns", summary.embeddedColumns(),
                "remainingCandidates", summary.remainingCandidates(),
                "method", "Caches low-cardinality value dictionaries in VaultDB for accurate filters and follow-up query generation"
            ));
            updateProgress(status, 50,
                "Cached value dictionaries for " + summary.cachedColumns() + " columns", taskRunId);
        } catch (Exception e) {
            log.warn("Column value collection failed for {} (non-fatal, continuing): {}", connectionId, e.getMessage());
            recordStageDetails(status, InitStage.COLUMN_VALUE_COLLECTION, Map.of(
                "status", "failed",
                "error", sanitizeError(e),
                "method", "Caches low-cardinality value dictionaries in VaultDB for accurate filters and follow-up query generation"
            ));
            updateProgress(status, 50, "Column value collection unavailable, continuing", taskRunId);
        }
        return InitStage.INFERRED_RELATIONSHIPS;
    }

    private InitStage executeInferredRelationships(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        try {
            var result = joinRelationshipInferenceService.inferRelationships(connectionId);
            recordStageDetails(status, InitStage.INFERRED_RELATIONSHIPS, Map.of(
                "totalRelationshipsInferred", result.getTotalRelationshipsInferred(),
                "highConfidenceCount", result.getHighConfidenceCount(),
                "newRelationshipsFound", result.getNewRelationshipsFound(),
                "existingRelationshipsUpdated", result.getExistingRelationshipsUpdated(),
                "totalQueriesAnalyzed", result.getTotalQueriesAnalyzed(),
                "queriesFromLineage", result.getQueriesFromLineage(),
                "queriesFromSlowLogs", result.getQueriesFromSlowLogs(),
                "parseFailures", result.getParseFailures(),
                "method", "Infers join paths from query lineage, slow logs, naming conventions, index patterns, and sampled data correlation"
            ));
            updateProgress(status, 60,
                "Inferred " + result.getTotalRelationshipsInferred() + " table relationships", taskRunId);
        } catch (Exception e) {
            log.warn("Relationship inference failed for {} (non-fatal, continuing): {}", connectionId, e.getMessage());
            recordStageDetails(status, InitStage.INFERRED_RELATIONSHIPS, Map.of(
                "status", "failed",
                "error", sanitizeError(e),
                "method", "Infers join paths from query lineage, slow logs, naming conventions, index patterns, and sampled data correlation"
            ));
            updateProgress(status, 60, "Relationship inference unavailable, continuing", taskRunId);
        }
        return InitStage.SCHEMA_CLASSIFICATION;
    }

    private InitStage executeSchemaClassification(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        try {
            var result = schemaClassificationService.classifySchema(connectionId);
            int totalTables = result != null ? result.getTotalTables() : 0;
            String pattern = result != null ? result.getGlobalPattern() : "UNKNOWN";
            recordStageDetails(status, InitStage.SCHEMA_CLASSIFICATION, Map.of(
                "totalTables", totalTables,
                "globalPattern", pattern,
                "method", "Detects schema patterns (STAR, SNOWFLAKE, OLTP) and classifies table roles (FACT, DIMENSION, BRIDGE)"
            ));
        } catch (Exception e) {
            log.warn("Schema classification failed for {} (non-fatal, continuing): {}", connectionId, e.getMessage());
            recordStageDetails(status, InitStage.SCHEMA_CLASSIFICATION, Map.of(
                "status", "failed",
                "error", sanitizeError(e),
                "method", "Detects schema patterns (STAR, SNOWFLAKE, OLTP) and classifies table roles (FACT, DIMENSION, BRIDGE)"
            ));
        }
        updateProgress(status, 68, "Schema classification complete", taskRunId);
        return InitStage.AI_DESCRIPTION;
    }

    private InitStage executeAiDescription(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        try {
            // Delta-only: skip tables that already have AI descriptions and only
            // describe new ones. A force rebuild regenerates everything by wiping
            // the existing AI docs first (see clearBrainMetadataForRebuild), so we
            // never need to force-regenerate from inside the normal pipeline.
            var result = schemaDescriptionService.generateDescriptions(connectionId, null,
                (processed, total, message) -> {
                    int pct = 68 + (int) ((processed / (double) Math.max(total, 1)) * 12);
                    updateProgress(status, pct, message, taskRunId);
                }, false);

            Map<String, Object> details = new HashMap<>();
            details.put("status", "completed");
            details.put("currentTables", result.getCurrentTables());
            details.put("matchedExistingAiTableDocs", result.getMatchedExistingAiTableDocs());
            details.put("unmatchedExistingAiTableDocs", result.getUnmatchedExistingAiTableDocs());
            details.put("coveredTables", result.getMatchedExistingAiTableDocs() + result.getTablesProcessed());
            details.put("descriptionsGenerated", result.getDocumentationsCreated());
            details.put("tablesProcessed", result.getTablesProcessed());
            details.put("tablesSkipped", result.getTablesSkipped());
            details.put("tablesFailed", result.getTablesFailed());
            details.put("method", "AI analyzes schema, sampled data, classifications, and inferred relationships to produce business-oriented descriptions");
            recordStageDetails(status, InitStage.AI_DESCRIPTION, details);
            updateProgress(status, 80,
                "Generated " + result.getDocumentationsCreated() + " descriptions", taskRunId);
        } catch (Exception e) {
            log.warn("AI description generation failed for {} (non-fatal, continuing): {}", connectionId, e.getMessage());
            Map<String, Object> details = new HashMap<>();
            details.put("status", "failed");
            details.put("currentTables", 0);
            details.put("matchedExistingAiTableDocs", 0);
            details.put("unmatchedExistingAiTableDocs", 0);
            details.put("coveredTables", 0);
            details.put("descriptionsGenerated", 0);
            details.put("tablesProcessed", 0);
            details.put("tablesSkipped", 0);
            details.put("tablesFailed", 0);
            details.put("error", sanitizeError(e));
            details.put("method", "AI analyzes schema, sampled data, classifications, and inferred relationships to produce business-oriented descriptions");
            recordStageDetails(status, InitStage.AI_DESCRIPTION, details);
            updateProgress(status, 80,
                "AI descriptions unavailable, continuing with schema metadata only", taskRunId);
        }
        return InitStage.RAG_EMBEDDING;
    }

    private InitStage executeRagEmbedding(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        var indexedTables = new AtomicInteger(0);
        var trainingSummary = new AtomicReference<TrainingRunSummary>();

        trainingJobService.startSchemaTrainingSync(connectionId, new TrainingService.TrainingProgressListener() {
            @Override
            public void onStart(int totalTables) {
                indexedTables.set(totalTables);
            }

            @Override
            public void onProgress(int processed, int total, String tableName) {
                indexedTables.set(total);
                int pct = 80 + (int) ((processed / (double) Math.max(total, 1)) * 12);
                updateProgress(status, pct,
                    "Indexing table " + tableName + " for retrieval (" + processed + "/" + total + ")",
                    taskRunId);
            }

            @Override
            public void onComplete() {
                // no-op
            }

            @Override
            public void onError(String message) {
                // the service throws on terminal errors; nothing else required here
            }

            @Override
            public void onRunSummary(TrainingRunSummary summary) {
                trainingSummary.set(summary);
            }
        });

        var summary = trainingSummary.get();
        int documentsIndexed = summary != null ? safeInt(summary.getAzureDocumentCount()) : 0;
        int embeddedDocuments = summary != null
                ? safeInt(summary.getEmbeddedDocumentCount() != null
                    ? summary.getEmbeddedDocumentCount()
                    : summary.getEmbeddingCount())
                : 0;

        boolean embeddingDegraded = vectorSearchService.isEnabled() && documentsIndexed > 0 && embeddedDocuments == 0;
        if (embeddingDegraded) {
            // Name the knob the resolver actually reads. AZURE_OPENAI_EMBEDDING_DEPLOYMENT
            // used to be named here and no longer configures anything —
            // LlmConfigResolver.resolveEmbedding() reads the DB bundle written by the setup
            // wizard, or DEEPSQL_EMBEDDING_*. Pointing an operator at a dead variable on the
            // one code path they hit when embeddings are broken is worse than saying nothing.
            log.warn("Brain init for {} indexed {} retrieval documents but 0 usable embeddings on {}. "
                + "Retrieval will use keyword-only search until embeddings are available. "
                + "Configure an embedding provider in the setup wizard, or set "
                + "DEEPSQL_EMBEDDING_PROVIDER / _MODEL / _API_KEY / _ENDPOINT; then check "
                + "embedding capacity on the provider.",
                connectionId, documentsIndexed, vectorSearchService.backendName());
        }

        Map<String, Object> details = new HashMap<>();
        details.put("tablesIndexed", indexedTables.get());
        details.put("documentsIndexed", documentsIndexed);
        details.put("embeddingCount", embeddedDocuments);
        details.put("embeddedDocumentCount", embeddedDocuments);
        if (embeddingDegraded) {
            // API- and UI-surfaced, so it must name a real knob for the same reason as above.
            details.put("embeddingWarning", "0 usable embeddings on " + vectorSearchService.backendName()
                + " — retrieval is keyword-only until an embedding provider is configured in the "
                + "setup wizard or via DEEPSQL_EMBEDDING_PROVIDER / _MODEL / _API_KEY");
        }
        if (summary != null && summary.getEmbeddingDimensions() != null) {
            details.put("embeddingDimensions", summary.getEmbeddingDimensions());
        }
        if (summary != null && summary.getDocumentCountsByType() != null && !summary.getDocumentCountsByType().isEmpty()) {
            details.put("documentCountsByType", summary.getDocumentCountsByType());
        }
        if (summary != null && summary.getEmbeddedDocumentCountsByType() != null && !summary.getEmbeddedDocumentCountsByType().isEmpty()) {
            details.put("embeddedDocumentCountsByType", summary.getEmbeddedDocumentCountsByType());
        }
        details.put("schemaDocsWatermark", asIsoString(schemaDocumentationRepository.findLatestTouchedAt(connectionId)));
        details.put("companyKnowledgeWatermark", asIsoString(companyKnowledgeEntryRepository.findLatestTouchedAt(connectionId)));
        details.put("schemaDocumentationCount", schemaDocumentationRepository.countByConnectionId(connectionId));
        details.put("companyKnowledgeCount", companyKnowledgeEntryRepository.countByConnectionId(connectionId));
        details.put("vectorBackend", vectorSearchService.backendName());
        details.put("method", "Builds the retrieval index from schema DDL, relationship docs, value summaries, table documentation, and business-term aliases after metadata learning is complete");
        recordStageDetails(status, InitStage.RAG_EMBEDDING, details);
        updateProgress(status, 92, "Knowledge base refreshed", taskRunId);
        return InitStage.BRAIN_ANALYSIS;
    }

    private InitStage executeBrainAnalysis(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        recordStageDetails(status, InitStage.BRAIN_ANALYSIS, Map.of(
            "method", "Cross-references schema relationships, index coverage, and documentation to enable intelligent query optimization"
        ));
        updateProgress(status, 96, "Brain analysis complete", taskRunId);
        return InitStage.SEMANTIC_MODELING;
    }

    private InitStage executeSemanticModeling(String connectionId, ConnectionInitStatus status, UUID taskRunId) {
        try {
            var summary = semanticModelService.rebuildSemanticModel(connectionId);
            recordStageDetails(status, InitStage.SEMANTIC_MODELING, Map.of(
                "status", "completed",
                "semanticTablesBuilt", summary.tablesBuilt(),
                "semanticJoinsBuilt", summary.joinsBuilt(),
                "tablesWithDocs", summary.tablesWithDocs(),
                "tablesWithTimeColumns", summary.tablesWithTimeColumns(),
                "verifiedPatterns", summary.verifiedPatterns(),
                "method", "Builds a vault-backed semantic layer for BI and schema reasoning using schema docs, key columns, relationships, value dictionaries, and approved query patterns"
            ));
            updateProgress(status, 100,
                "Built semantic model for " + summary.tablesBuilt() + " tables", taskRunId);
        } catch (Exception e) {
            log.warn("Semantic model build failed for {} (non-fatal, continuing): {}", connectionId, e.getMessage());
            recordStageDetails(status, InitStage.SEMANTIC_MODELING, Map.of(
                "status", "failed",
                "error", sanitizeError(e),
                "method", "Builds a vault-backed semantic layer for BI and schema reasoning using schema docs, key columns, relationships, value dictionaries, and approved query patterns"
            ));
            updateProgress(status, 100,
                "Semantic model unavailable, continuing with raw metadata", taskRunId);
        }
        markCompleted(status, taskRunId);
        return null;
    }

    // ── Status helpers (all re-validate runId before writing) ──────────

    private boolean isStaleOrCancelled(String connectionId, UUID taskRunId) {
        return initStatusRepo.findById(connectionId)
            .map(s -> !taskRunId.equals(s.getActiveRunId())
                    || Boolean.TRUE.equals(s.getCancelRequested()))
            .orElse(true);
    }

    private boolean updateStage(ConnectionInitStatus status, InitStage stage,
            int pct, String msg, UUID taskRunId) {
        var current = initStatusRepo.findById(status.getConnectionId());
        if (current.isEmpty() || !taskRunId.equals(current.get().getActiveRunId())) {
            log.info("Run {} superseded for {}, stopping", taskRunId, status.getConnectionId());
            return false;
        }
        if (Boolean.TRUE.equals(current.get().getCancelRequested())) {
            log.info("Run {} cancelled for {}, stopping", taskRunId, status.getConnectionId());
            return false;
        }
        ConnectionInitStatus fresh = current.get();
        if (stage != fresh.getCurrentStage()) {
            closeActiveStage(fresh);
            recordStageStart(fresh, stage);
        }
        fresh.setCurrentStage(stage);
        fresh.setProgressPercent(pct);
        fresh.setStageMessage(msg);
        initStatusRepo.save(fresh);
        broadcast(fresh.getConnectionId(), fresh);
        return true;
    }

    private void updateProgress(ConnectionInitStatus status, int pct, String msg, UUID taskRunId) {
        if (isStaleOrCancelled(status.getConnectionId(), taskRunId)) return;
        var current = initStatusRepo.findById(status.getConnectionId());
        if (current.isEmpty()) return;
        ConnectionInitStatus fresh = current.get();
        fresh.setProgressPercent(pct);
        fresh.setStageMessage(msg);
        try {
            initStatusRepo.save(fresh);
            broadcast(fresh.getConnectionId(), fresh);
        } catch (Exception e) {
            log.warn("Failed to persist progress for {}: {}", status.getConnectionId(), e.getMessage());
        }
    }

    private void markFailed(ConnectionInitStatus status, String error, UUID taskRunId) {
        var current = initStatusRepo.findById(status.getConnectionId());
        if (current.isEmpty() || !taskRunId.equals(current.get().getActiveRunId())) {
            return;
        }
        ConnectionInitStatus fresh = current.get();
        closeActiveStage(fresh);
        fresh.setCurrentStage(InitStage.FAILED);
        fresh.setErrorMessage(error);
        fresh.setCompletedAt(LocalDateTime.now());
        initStatusRepo.save(fresh);
        broadcast(fresh.getConnectionId(), fresh);
        saveHistory(fresh);
    }

    private void markCompleted(ConnectionInitStatus status, UUID taskRunId) {
        var current = initStatusRepo.findById(status.getConnectionId());
        if (current.isEmpty() || !taskRunId.equals(current.get().getActiveRunId())) {
            return;
        }
        ConnectionInitStatus fresh = current.get();
        closeActiveStage(fresh);
        fresh.setCurrentStage(InitStage.COMPLETED);
        fresh.setProgressPercent(100);
        fresh.setStageMessage("All set! Brain is ready.");
        fresh.setCompletedAt(LocalDateTime.now());
        initStatusRepo.save(fresh);
        broadcast(fresh.getConnectionId(), fresh);
        saveHistory(fresh);
    }

    // ── Helpers (ported from ConnectionInitPipeline) ──────────────────

    private int stageStartPercent(InitStage stage) {
        return switch (stage) {
            case SCHEMA_SCAN -> 0;
            case DATA_SAMPLING -> 18;
            case KEY_COLUMN_ANALYSIS -> 30;
            case COLUMN_VALUE_COLLECTION -> 40;
            case INFERRED_RELATIONSHIPS -> 50;
            case SCHEMA_CLASSIFICATION -> 60;
            case AI_DESCRIPTION -> 68;
            case RAG_EMBEDDING -> 80;
            case BRAIN_ANALYSIS -> 92;
            case SEMANTIC_MODELING -> 96;
            case COMPLETED, FAILED -> 100;
        };
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private String asIsoString(LocalDateTime value) {
        return value != null ? value.toString() : "";
    }

    private LocalDateTime latest(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private void recordStageStart(ConnectionInitStatus status, InitStage stage) {
        status.getStageTimings().put(stage.name(),
            new StageTimingEntry(Instant.now().toString(), null, 0));
    }

    private void closeActiveStage(ConnectionInitStatus status) {
        var timings = status.getStageTimings();
        var entry = timings.get(status.getCurrentStage().name());
        if (entry != null && entry.endedAt() == null) {
            Instant start = Instant.parse(entry.startedAt());
            Instant end = Instant.now();
            long durationMs = Duration.between(start, end).toMillis();
            timings.put(status.getCurrentStage().name(),
                new StageTimingEntry(entry.startedAt(), end.toString(), durationMs));
        }
    }

    private void recordStageDetails(ConnectionInitStatus status, InitStage stage, Map<String, Object> details) {
        status.getStageDetails().put(stage.name(), new HashMap<>(details));
    }

    private void broadcast(String connectionId, ConnectionInitStatus status) {
        trainingJobService.broadcastInitProgress(connectionId, status);
    }

    private void saveHistory(ConnectionInitStatus status) {
        try {
            long totalMs = 0;
            if (status.getStartedAt() != null && status.getCompletedAt() != null) {
                totalMs = Duration.between(
                    status.getStartedAt().atZone(ZoneId.systemDefault()).toInstant(),
                    status.getCompletedAt().atZone(ZoneId.systemDefault()).toInstant()
                ).toMillis();
            }
            var history = ConnectionInitHistory.builder()
                .connectionId(status.getConnectionId())
                .finalStage(status.getCurrentStage())
                .progressPercent(status.getProgressPercent())
                .stageTimings(status.getStageTimings())
                .stageDetails(status.getStageDetails())
                .startedAt(status.getStartedAt())
                .completedAt(status.getCompletedAt())
                .totalDurationMs(totalMs)
                .errorMessage(status.getErrorMessage())
                .build();
            initHistoryRepo.save(history);
        } catch (Exception e) {
            log.warn("Failed to save init history for {}: {}", status.getConnectionId(), e.getMessage());
        }
    }

    private String sanitizeError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "unknown";
        msg = msg.replaceAll("jdbc:[^\\s]+", "[connection]");
        msg = msg.replaceAll("(?i)for user [\"']?\\w+[\"']?", "for user [redacted]");
        msg = msg.replaceAll("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b", "[ip]");
        msg = e.getClass().getSimpleName() + ": " + msg;
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
