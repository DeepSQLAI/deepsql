package com.dbaagent.controller;

import com.dbaagent.dto.KeyColumnAnalysisResult;
import com.dbaagent.dto.NoteSuggestionDTO;
import com.dbaagent.dto.RelationshipInferenceResult;
import com.dbaagent.model.brain.BrainProfileResult;
import com.dbaagent.model.brain.BrainNoteRequest;
import com.dbaagent.model.brain.BrainNoteResponse;
import com.dbaagent.model.brain.BrainScore;
import com.dbaagent.model.brain.BrainTaskRequest;
import com.dbaagent.model.brain.BrainTaskResponse;
import com.dbaagent.model.brain.BrainUnderstandingResponse;
import com.dbaagent.model.ColumnAntiPattern;
import com.dbaagent.model.ColumnDisambiguation;
import com.dbaagent.model.ColumnValueCache;
import com.dbaagent.model.ConfigurationRecommendation;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.QueryAntiPattern;
import com.dbaagent.model.ScalabilitySimulation;
import com.dbaagent.model.SchemaClassification;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableGrowthPrediction;
import com.dbaagent.model.TableRelationshipClassification;
import com.dbaagent.model.brain.*;
import com.dbaagent.service.brain.core.BrainService;
import com.dbaagent.service.brain.core.BrainScoreService;
import com.dbaagent.service.brain.core.BrainNoteService;
import com.dbaagent.service.brain.core.BrainTaskService;
import com.dbaagent.service.brain.core.NoteSuggestionService;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.SchemaSnapshotService;
import com.dbaagent.service.brain.analysis.ColumnDisambiguationService;
import com.dbaagent.service.brain.analysis.ColumnProfilingService;
import com.dbaagent.service.brain.analysis.QueryQualityAnalysisService;
import com.dbaagent.service.brain.analysis.ScalabilitySimulationService;
import com.dbaagent.service.brain.keycolumn.ColumnValueCollectionService;
import com.dbaagent.service.brain.keycolumn.JoinRelationshipInferenceService;
import com.dbaagent.service.brain.keycolumn.KeyColumnAnalysisService;
import com.dbaagent.service.brain.classification.SchemaClassificationService;
import com.dbaagent.service.brain.classification.AccessPatternClassificationService;
import com.dbaagent.service.brain.classification.AntiPatternDetectionService;
import com.dbaagent.service.brain.classification.TemporalClassificationService;
import com.dbaagent.service.brain.classification.TableHealthScoreService;
import com.dbaagent.service.brain.classification.BusinessDomainClassificationService;
import com.dbaagent.service.brain.classification.DataSensitivityClassificationService;
import com.dbaagent.service.brain.classification.PartitionReadinessService;
import com.dbaagent.service.brain.classification.RelationshipClassificationService;
import com.dbaagent.service.brain.classification.DataLifecycleClassificationService;
import com.dbaagent.service.brain.classification.CacheAffinityClassificationService;
import com.dbaagent.service.brain.classification.QueryComplexityClassificationService;
import com.dbaagent.service.brain.classification.SchemaEvolutionRiskService;
import com.dbaagent.service.brain.classification.DenormalizationCandidateService;
import com.dbaagent.service.brain.classification.CostAttributionService;
import com.dbaagent.service.brain.classification.ShardingReadinessService;
import com.dbaagent.service.brain.classification.DataQualityScoreService;
import com.dbaagent.service.brain.classification.DependencyCriticalityService;
import com.dbaagent.service.brain.classification.GrowthPredictionService;
import com.dbaagent.service.brain.workload.WorkloadMetricsCollectorService;
import com.dbaagent.service.brain.workload.WorkloadCharacterizationService;
import com.dbaagent.service.brain.config.KnobIdentificationService;
import com.dbaagent.service.brain.config.ConfigTuningService;
import com.dbaagent.service.brain.query.CardinalityEstimationService;
import com.dbaagent.service.brain.query.AdaptivePlanScoringService;
import com.dbaagent.service.brain.query.PlanPatternLibraryService;
import com.dbaagent.service.brain.BrainInsightEmbeddingService;
import com.dbaagent.service.SlowQueryHistoryService;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.model.SlowQueryAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/brain")
@RequiredArgsConstructor
@Slf4j
public class BrainController {
    private final BrainService brainService;
    private final BrainScoreService brainScoreService;
    private final BrainNoteService brainNoteService;
    private final BrainTaskService brainTaskService;
    private final NoteSuggestionService noteSuggestionService;
    private final ColumnProfilingService columnProfilingService;
    private final ColumnDisambiguationService columnDisambiguationService;
    private final SchemaSnapshotService schemaSnapshotService;
    private final QueryExecutorService queryExecutorService;
    private final KeyColumnAnalysisService keyColumnAnalysisService;
    private final SchemaClassificationService schemaClassificationService;
    private final QueryQualityAnalysisService queryQualityAnalysisService;
    private final ScalabilitySimulationService scalabilitySimulationService;
    private final JoinRelationshipInferenceService joinRelationshipInferenceService;

    // Enhanced classification services (Phase 1-5)
    private final AccessPatternClassificationService accessPatternService;
    private final AntiPatternDetectionService antiPatternService;
    private final TemporalClassificationService temporalService;
    private final TableHealthScoreService healthScoreService;
    private final BusinessDomainClassificationService domainService;
    private final DataSensitivityClassificationService sensitivityService;
    private final PartitionReadinessService partitionService;
    private final RelationshipClassificationService relationshipClassificationService;

    // Phase 6 classification services
    private final DataLifecycleClassificationService lifecycleService;
    private final CacheAffinityClassificationService cacheAffinityService;
    private final QueryComplexityClassificationService queryComplexityService;
    private final SchemaEvolutionRiskService schemaRiskService;
    private final DenormalizationCandidateService denormalizationService;
    private final CostAttributionService costService;
    private final ShardingReadinessService shardingService;
    private final DataQualityScoreService dataQualityService;
    private final DependencyCriticalityService dependencyCriticalityService;
    private final GrowthPredictionService growthPredictionService;

    // Column value collection service
    private final ColumnValueCollectionService columnValueCollectionService;

    // Brain ML Services
    private final WorkloadMetricsCollectorService metricsCollectorService;
    private final WorkloadCharacterizationService workloadCharacterizationService;
    private final KnobIdentificationService knobIdentificationService;
    private final ConfigTuningService configTuningService;
    private final CardinalityEstimationService cardinalityEstimationService;
    private final AdaptivePlanScoringService adaptivePlanScoringService;
    private final PlanPatternLibraryService planPatternLibraryService;
    private final SlowQueryHistoryService slowQueryHistoryService;
    private final BrainInsightEmbeddingService brainInsightEmbeddingService;
    private final AccessControlService accessControlService;

    @GetMapping("/understanding/{connectionId}")
    public ResponseEntity<BrainUnderstandingResponse> getUnderstanding(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(brainService.getUnderstanding(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching brain understanding", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/profile/{connectionId}")
    public ResponseEntity<BrainProfileResult> profileConnection(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            return ResponseEntity.ok(columnProfilingService.profileConnection(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error profiling connection", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/ambiguity")
    public ResponseEntity<Map<String, String>> resolveAmbiguity(
        @org.springframework.web.bind.annotation.RequestBody Map<String, String> request
    ) {
        try {
            String connectionId = request.get("connectionId");
            accessControlService.assertCanManageConnectionContent(connectionId);
            String columnName = request.get("columnName");
            String preferredTable = request.get("preferredTable");
            ColumnDisambiguation saved = columnDisambiguationService.upsert(
                connectionId,
                columnName,
                preferredTable
            );
            return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "columnName", saved.getColumnName(),
                "preferredTable", saved.getPreferredTable()
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error resolving ambiguity", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/schema/rescan/{connectionId}")
    public ResponseEntity<Map<String, Object>> rescanSchema(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            queryExecutorService.evictDatabaseObjectsCache(connectionId);
            SchemaSnapshot snapshot = schemaSnapshotService.captureSnapshot(connectionId, true);
            return ResponseEntity.ok(Map.of(
                "connectionId", connectionId,
                "capturedAt", snapshot != null ? snapshot.getCapturedAt() : null
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error rescanning schema", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get suggested tables/columns that need documentation based on Brain analysis.
     * Uses Key Column Analysis, Schema Classification, and Disambiguation data.
     * NOTE: This endpoint must be defined BEFORE /notes/{connectionId} to avoid path conflicts.
     */
    @GetMapping("/notes/suggestions/{connectionId}")
    public ResponseEntity<NoteSuggestionDTO.Response> getNoteSuggestions(
        @PathVariable String connectionId,
        @RequestParam(required = false, defaultValue = "10") int limit
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(noteSuggestionService.getSuggestions(connectionId, limit));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching note suggestions for connection {}", connectionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/notes/{connectionId}")
    public ResponseEntity<List<BrainNoteResponse>> getNotes(
        @PathVariable String connectionId,
        @RequestParam(required = false) String scopeType,
        @RequestParam(required = false) String tableName,
        @RequestParam(required = false) String columnName
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(brainNoteService.getNotes(connectionId, scopeType, tableName, columnName));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching brain notes", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/notes")
    public ResponseEntity<BrainNoteResponse> createNote(
        @org.springframework.web.bind.annotation.RequestBody BrainNoteRequest request
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(request.getConnectionId());
            return ResponseEntity.ok(brainNoteService.createNote(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating brain note", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/notes/{noteId}")
    public ResponseEntity<BrainNoteResponse> updateNote(
        @PathVariable String noteId,
        @org.springframework.web.bind.annotation.RequestBody BrainNoteRequest request
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(brainNoteService.getConnectionId(noteId));
            return ResponseEntity.ok(brainNoteService.updateNote(noteId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error updating brain note", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/notes/{noteId}/delete")
    public ResponseEntity<Map<String, String>> deleteNote(
        @PathVariable String noteId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(brainNoteService.getConnectionId(noteId));
            brainNoteService.deleteNote(noteId);
            return ResponseEntity.ok(Map.of("message", "Note deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting brain note", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/tasks/{connectionId}")
    public ResponseEntity<List<BrainTaskResponse>> getTasks(
        @PathVariable String connectionId,
        @RequestParam(required = false) String status
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(brainTaskService.getTasks(connectionId, status));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching brain tasks", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/tasks")
    public ResponseEntity<BrainTaskResponse> createTask(
        @org.springframework.web.bind.annotation.RequestBody BrainTaskRequest request
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(request.getConnectionId());
            return ResponseEntity.ok(brainTaskService.createTask(request));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating brain task", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/tasks/{taskId}/status")
    public ResponseEntity<BrainTaskResponse> updateTaskStatus(
        @PathVariable String taskId,
        @org.springframework.web.bind.annotation.RequestBody Map<String, String> request
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(brainTaskService.getConnectionId(taskId));
            return ResponseEntity.ok(brainTaskService.updateStatus(taskId, request.get("status")));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error updating brain task status", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/key-columns/{connectionId}")
    public ResponseEntity<KeyColumnAnalysisResult> getKeyColumns(
        @PathVariable String connectionId,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String tableName,
        @RequestParam(required = false) Boolean antiPatternsOnly
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(keyColumnAnalysisService.getKeyColumns(
                connectionId, limit, tableName, antiPatternsOnly));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching key columns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/key-columns/analyze/{connectionId}")
    public ResponseEntity<KeyColumnAnalysisResult> analyzeKeyColumns(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            return ResponseEntity.ok(keyColumnAnalysisService.analyzeKeyColumns(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing key columns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Not implemented. Kept admin-only rather than guarded per connection: the body
     * never loads the anti-pattern, so there is no connection to authorize against.
     * Whoever implements it must resolve the owning connection from {@code patternId}
     * and switch to {@code assertCanManageConnectionContent}.
     */
    @PostMapping("/key-columns/anti-pattern/{patternId}/acknowledge")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> acknowledgeAntiPattern(
        @PathVariable Long patternId
    ) {
        try {
            // TODO: Implement acknowledgment logic in KeyColumnAnalysisService
            return ResponseEntity.ok(Map.of(
                "message", "Anti-pattern acknowledgment not yet implemented",
                "patternId", String.valueOf(patternId)
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error acknowledging anti-pattern", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/key-columns/cleanup/{connectionId}")
    public ResponseEntity<Map<String, String>> cleanupKeyColumnsData(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            keyColumnAnalysisService.cleanupOldAnalysisData(connectionId);
            return ResponseEntity.ok(Map.of(
                "message", "Successfully cleaned up old key column analysis data",
                "connectionId", connectionId
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cleaning up key column data", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Schema Classification Endpoints ====================

    @GetMapping("/schema-classification/{connectionId}")
    public ResponseEntity<SchemaClassification> getSchemaClassification(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return schemaClassificationService.getLatestClassification(connectionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching schema classification", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/schema-classification/analyze/{connectionId}")
    public ResponseEntity<SchemaClassification> analyzeSchemaClassification(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            SchemaClassification result = schemaClassificationService.classifySchema(connectionId);
            return ResponseEntity.ok(result);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing schema classification", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/tables/{connectionId}")
    public ResponseEntity<List<TableClassification>> getTableClassifications(
        @PathVariable String connectionId,
        @RequestParam(required = false) String role
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables;
            if (role != null && !role.isEmpty()) {
                tables = schemaClassificationService.getTablesByRole(connectionId, role);
            } else {
                tables = schemaClassificationService.getAllTableClassifications(connectionId);
            }
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching table classifications", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/fact-tables/{connectionId}")
    public ResponseEntity<List<TableClassification>> getFactTables(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(schemaClassificationService.getFactTables(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching fact tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/dimensions/{connectionId}")
    public ResponseEntity<List<TableClassification>> getDimensionTables(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(schemaClassificationService.getDimensionTables(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching dimension tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Inferred Relationships Endpoints ====================

    @GetMapping("/inferred-relationships/{connectionId}")
    public ResponseEntity<List<InferredTableRelationship>> getInferredRelationships(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(joinRelationshipInferenceService.getRelationships(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching inferred relationships", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/inferred-relationships/analyze/{connectionId}")
    public ResponseEntity<RelationshipInferenceResult> analyzeInferredRelationships(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            RelationshipInferenceResult result = joinRelationshipInferenceService.inferRelationships(connectionId);
            return ResponseEntity.ok(result);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing inferred relationships", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/inferred-relationships/{id}/validate")
    public ResponseEntity<InferredTableRelationship> validateInferredRelationship(
        @PathVariable String id,
        @org.springframework.web.bind.annotation.RequestBody Map<String, String> request
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(joinRelationshipInferenceService.getConnectionId(id));
            String status = request.getOrDefault("status", "VALIDATED");
            if (!status.equals("VALIDATED") && !status.equals("REJECTED")) {
                return ResponseEntity.badRequest().build();
            }
            InferredTableRelationship updated = joinRelationshipInferenceService.updateRelationshipStatus(id, status);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error validating inferred relationship", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Query Quality Anti-Patterns Endpoints ====================

    @GetMapping("/query-anti-patterns/{connectionId}")
    public ResponseEntity<List<QueryAntiPattern>> getQueryAntiPatterns(
        @PathVariable String connectionId,
        @RequestParam(required = false) Integer limit
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<QueryAntiPattern> patterns;
            if (limit != null && limit > 0) {
                patterns = queryQualityAnalysisService.getQueryAntiPatterns(connectionId, limit);
            } else {
                patterns = queryQualityAnalysisService.getAllQueryAntiPatterns(connectionId);
            }
            return ResponseEntity.ok(patterns);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching query anti-patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/query-anti-patterns/analyze/{connectionId}")
    public ResponseEntity<Map<String, Object>> analyzeQueryAntiPatterns(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            queryQualityAnalysisService.analyzeQueryQuality(connectionId);
            List<QueryAntiPattern> patterns = queryQualityAnalysisService.getAllQueryAntiPatterns(connectionId);

            long criticalCount = patterns.stream()
                .filter(p -> p.getSeverity() == QueryAntiPattern.Severity.CRITICAL)
                .count();
            long highCount = patterns.stream()
                .filter(p -> p.getSeverity() == QueryAntiPattern.Severity.HIGH)
                .count();

            return ResponseEntity.ok(Map.of(
                "message", "Analysis complete",
                "totalPatterns", patterns.size(),
                "criticalCount", criticalCount,
                "highCount", highCount,
                "patterns", patterns
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing query anti-patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/query-anti-patterns/by-severity/{connectionId}/{severity}")
    public ResponseEntity<List<QueryAntiPattern>> getQueryAntiPatternsBySeverity(
        @PathVariable String connectionId,
        @PathVariable String severity
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            QueryAntiPattern.Severity severityEnum = QueryAntiPattern.Severity.valueOf(severity.toUpperCase());
            // Note: Need to add this method to QueryQualityAnalysisService
            return ResponseEntity.ok(List.of()); // TODO: Implement in service
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching query anti-patterns by severity", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Scalability Simulation Endpoints ====================

    @GetMapping("/scalability/{connectionId}")
    public ResponseEntity<List<ScalabilitySimulation>> getScalabilitySimulations(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(scalabilitySimulationService.getAllSimulations(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching scalability simulations", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/scalability/latest/{connectionId}")
    public ResponseEntity<ScalabilitySimulation> getLatestSimulation(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return scalabilitySimulationService.getLatestSimulation(connectionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching latest simulation", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/scalability/simulate/{connectionId}")
    public ResponseEntity<List<ScalabilitySimulation>> runScalabilitySimulation(
        @PathVariable String connectionId,
        @RequestParam(required = false) String scenario
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            if (scenario != null && !scenario.isEmpty()) {
                // Run single scenario
                ScalabilitySimulation.GrowthScenario scenarioEnum =
                    ScalabilitySimulation.GrowthScenario.valueOf("GROWTH_" + scenario.toUpperCase());
                ScalabilitySimulation result = scalabilitySimulationService.simulateGrowth(connectionId, scenarioEnum);
                return ResponseEntity.ok(List.of(result));
            } else {
                // Run all scenarios
                List<ScalabilitySimulation> results = scalabilitySimulationService.simulateAllScenarios(connectionId);
                return ResponseEntity.ok(results);
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error running scalability simulation", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/scalability/predictions/{simulationId}")
    public ResponseEntity<List<TableGrowthPrediction>> getTablePredictions(
        @PathVariable String simulationId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(scalabilitySimulationService.getConnectionId(simulationId));
            return ResponseEntity.ok(scalabilitySimulationService.getTablePredictions(simulationId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching table predictions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/scalability/high-risk/{connectionId}")
    public ResponseEntity<List<TableGrowthPrediction>> getHighRiskTables(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(scalabilitySimulationService.getHighRiskTables(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching high-risk tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain Score Endpoints ====================

    @GetMapping("/score/latest/{connectionId}")
    public ResponseEntity<BrainScore> getLatestBrainScore(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return brainScoreService.getLatestBrainScore(connectionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching latest Brain Score", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/score/history/{connectionId}")
    public ResponseEntity<List<BrainScore>> getBrainScoreHistory(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "10") int limit
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(brainScoreService.getBrainScoreHistory(connectionId, limit));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching Brain Score history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/score/calculate/{connectionId}")
    public ResponseEntity<BrainScore> calculateBrainScore(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            BrainScore score = brainScoreService.calculateBrainScore(connectionId);
            return ResponseEntity.ok(score);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calculating Brain Score", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Enhanced Classification Endpoints ====================

    @GetMapping("/access-patterns/{connectionId}")
    public ResponseEntity<Map<String, AccessPatternClassificationService.TableAccessPattern>> getAccessPatterns(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(accessPatternService.classifyAccessPatterns(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting access patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/table-anti-patterns/{connectionId}")
    public ResponseEntity<Map<String, AntiPatternDetectionService.TableAntiPatterns>> getTableAntiPatterns(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(antiPatternService.detectAntiPatterns(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting table anti-patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/temporal-classification/{connectionId}")
    public ResponseEntity<Map<String, TemporalClassificationService.TemporalClassification>> getTemporalClassification(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(temporalService.classifyTemporalPatterns(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting temporal classification", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/health-scores/{connectionId}")
    public ResponseEntity<Map<String, TableHealthScoreService.TableHealthScore>> getHealthScores(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(healthScoreService.calculateHealthScores(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting health scores", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/business-domains/{connectionId}")
    public ResponseEntity<Map<String, BusinessDomainClassificationService.DomainClassification>> getBusinessDomains(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(domainService.classifyBusinessDomains(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting business domains", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/data-sensitivity/{connectionId}")
    public ResponseEntity<Map<String, DataSensitivityClassificationService.SensitivityClassification>> getDataSensitivity(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(sensitivityService.classifyDataSensitivity(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting data sensitivity", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/partition-readiness/{connectionId}")
    public ResponseEntity<Map<String, PartitionReadinessService.PartitionReadiness>> getPartitionReadiness(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(partitionService.evaluatePartitionReadiness(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting partition readiness", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/relationship-classification/{connectionId}")
    public ResponseEntity<List<TableRelationshipClassification>> getRelationshipClassification(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(relationshipClassificationService.getRelationships(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting relationship classification", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/relationship-classification/integrity-issues/{connectionId}")
    public ResponseEntity<List<TableRelationshipClassification>> getIntegrityIssues(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(relationshipClassificationService.getIntegrityIssues(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting integrity issues", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/relationship-classification/missing-indexes/{connectionId}")
    public ResponseEntity<List<TableRelationshipClassification>> getMissingRelationshipIndexes(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(relationshipClassificationService.getMissingIndexes(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting missing indexes", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/tables-by-sensitivity/{connectionId}/{level}")
    public ResponseEntity<List<TableClassification>> getTablesBySensitivity(
        @PathVariable String connectionId,
        @PathVariable String level
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> level.equalsIgnoreCase(tc.getSensitivityLevel()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting tables by sensitivity", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/tables-by-domain/{connectionId}/{domain}")
    public ResponseEntity<List<TableClassification>> getTablesByDomain(
        @PathVariable String connectionId,
        @PathVariable String domain
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> domain.equalsIgnoreCase(tc.getBusinessDomain()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting tables by domain", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/partition-candidates/{connectionId}")
    public ResponseEntity<List<TableClassification>> getPartitionCandidates(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> tc.getPartitionReadiness() != null &&
                             tc.getPartitionReadiness().startsWith("PARTITION_CANDIDATE"))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting partition candidates", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/tables-with-anti-patterns/{connectionId}")
    public ResponseEntity<List<TableClassification>> getTablesWithAntiPatterns(
        @PathVariable String connectionId,
        @RequestParam(required = false) String severity
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> tc.getAntiPatternCount() != null && tc.getAntiPatternCount() > 0)
                .filter(tc -> severity == null || severity.equalsIgnoreCase(tc.getAntiPatternSeverity()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting tables with anti-patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/low-health-tables/{connectionId}")
    public ResponseEntity<List<TableClassification>> getLowHealthTables(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "60") double threshold
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> tc.getHealthScore() != null && tc.getHealthScore().doubleValue() < threshold)
                .sorted((a, b) -> a.getHealthScore().compareTo(b.getHealthScore()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting low health tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Phase 6 Classification Endpoints ====================

    @GetMapping("/data-lifecycle/{connectionId}")
    public ResponseEntity<Map<String, DataLifecycleClassificationService.DataLifecycle>> getDataLifecycle(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(lifecycleService.classifyDataLifecycle(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting data lifecycle classification", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/cache-affinity/{connectionId}")
    public ResponseEntity<Map<String, CacheAffinityClassificationService.CacheAffinity>> getCacheAffinity(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(cacheAffinityService.classifyCacheAffinity(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting cache affinity classification", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/query-complexity/{connectionId}")
    public ResponseEntity<Map<String, QueryComplexityClassificationService.QueryComplexity>> getQueryComplexity(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(queryComplexityService.classifyQueryComplexity(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting query complexity classification", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-evolution-risk/{connectionId}")
    public ResponseEntity<Map<String, SchemaEvolutionRiskService.SchemaEvolutionRisk>> getSchemaEvolutionRisk(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(schemaRiskService.assessSchemaEvolutionRisk(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting schema evolution risk", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/denormalization-candidates/{connectionId}")
    public ResponseEntity<Map<String, DenormalizationCandidateService.DenormalizationCandidate>> getDenormalizationCandidates(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(denormalizationService.identifyDenormalizationCandidates(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting denormalization candidates", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/cost-attribution/{connectionId}")
    public ResponseEntity<Map<String, CostAttributionService.CostAttribution>> getCostAttribution(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(costService.calculateCostAttribution(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting cost attribution", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/sharding-readiness/{connectionId}")
    public ResponseEntity<Map<String, ShardingReadinessService.ShardingReadiness>> getShardingReadiness(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(shardingService.assessShardingReadiness(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting sharding readiness", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/data-quality/{connectionId}")
    public ResponseEntity<Map<String, DataQualityScoreService.DataQualityScore>> getDataQuality(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(dataQualityService.calculateDataQuality(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting data quality scores", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/dependency-criticality/{connectionId}")
    public ResponseEntity<Map<String, DependencyCriticalityService.DependencyCriticality>> getDependencyCriticality(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(dependencyCriticalityService.assessDependencyCriticality(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting dependency criticality", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/growth-prediction/{connectionId}")
    public ResponseEntity<Map<String, GrowthPredictionService.GrowthPrediction>> getGrowthPrediction(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(growthPredictionService.predictGrowth(connectionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting growth predictions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Phase 6 Filtered Classification Endpoints ====================

    @GetMapping("/schema-classification/tables-by-lifecycle/{connectionId}/{lifecycle}")
    public ResponseEntity<List<TableClassification>> getTablesByLifecycle(
        @PathVariable String connectionId,
        @PathVariable String lifecycle
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> lifecycle.equalsIgnoreCase(tc.getDataLifecycle()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting tables by lifecycle", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/high-cache-value/{connectionId}")
    public ResponseEntity<List<TableClassification>> getHighCacheValueTables(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> "HIGH_CACHE_VALUE".equalsIgnoreCase(tc.getCacheAffinity()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting high cache value tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/schema-risk/{connectionId}/{level}")
    public ResponseEntity<List<TableClassification>> getTablesBySchemaRisk(
        @PathVariable String connectionId,
        @PathVariable String level
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> level.equalsIgnoreCase(tc.getSchemaEvolutionRisk()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting tables by schema risk", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/denormalization-candidates/{connectionId}")
    public ResponseEntity<List<TableClassification>> getDenormalizationCandidateTables(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> Boolean.TRUE.equals(tc.getDenormalizationCandidate()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting denormalization candidate tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/sharding-ready/{connectionId}")
    public ResponseEntity<List<TableClassification>> getShardingReadyTables(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> "READY".equalsIgnoreCase(tc.getShardingReadiness()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting sharding ready tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/low-quality/{connectionId}")
    public ResponseEntity<List<TableClassification>> getLowQualityTables(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "50") double threshold
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> tc.getDataQualityScore() != null && tc.getDataQualityScore().doubleValue() < threshold)
                .sorted((a, b) -> a.getDataQualityScore().compareTo(b.getDataQualityScore()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting low quality tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/high-dependency-criticality/{connectionId}")
    public ResponseEntity<List<TableClassification>> getHighDependencyCriticalityTables(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> "CRITICAL".equalsIgnoreCase(tc.getDependencyCriticality()) ||
                             "HIGH".equalsIgnoreCase(tc.getDependencyCriticality()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting high dependency criticality tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/rapid-growth/{connectionId}")
    public ResponseEntity<List<TableClassification>> getRapidGrowthTables(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> "EXPLOSIVE".equalsIgnoreCase(tc.getGrowthCategory()) ||
                             "RAPID".equalsIgnoreCase(tc.getGrowthCategory()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting rapid growth tables", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema-classification/cost-tier/{connectionId}/{tier}")
    public ResponseEntity<List<TableClassification>> getTablesByCostTier(
        @PathVariable String connectionId,
        @PathVariable String tier
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TableClassification> tables = schemaClassificationService.getAllTableClassifications(connectionId)
                .stream()
                .filter(tc -> tier.equalsIgnoreCase(tc.getCostTier()))
                .toList();
            return ResponseEntity.ok(tables);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting tables by cost tier", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Column Values Endpoints ====================

    /**
     * Get all cached column values for a connection.
     * Optionally filter by table name.
     */
    @GetMapping("/column-values/{connectionId}")
    public ResponseEntity<List<ColumnValueCache>> getColumnValues(
        @PathVariable String connectionId,
        @RequestParam(required = false) String tableName
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<ColumnValueCache> values = columnValueCollectionService.getCachedColumns(connectionId, tableName);
            return ResponseEntity.ok(values);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching column values", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get statistics about cached column values.
     */
    @GetMapping("/column-values/{connectionId}/stats")
    public ResponseEntity<Map<String, Object>> getColumnValueStats(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> stats = columnValueCollectionService.getStatistics(connectionId);
            return ResponseEntity.ok(stats);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching column value statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Manually trigger column value collection and embedding.
     * Useful for refreshing values after schema changes.
     */
    @PostMapping("/column-values/{connectionId}/refresh")
    public ResponseEntity<Map<String, Object>> refreshColumnValues(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Manually triggering column value refresh for connection: {}", connectionId);

            // Run async and return immediately
            Thread.ofVirtual().start(() -> {
                try {
                    columnValueCollectionService.analyzeColumnValues(connectionId, null);
                } catch (ResponseStatusException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("Error during column value refresh: {}", e.getMessage());
                }
            });

            return ResponseEntity.ok(Map.of(
                "message", "Column value refresh started",
                "connectionId", connectionId,
                "status", "RUNNING"
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error starting column value refresh", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Embed all unembedded column values.
     * Useful for re-syncing Azure AI Search after migration or data loss.
     *
     * <p>Spans every connection with no per-connection scope, so it cannot be
     * authorized against a single connection's grants — admin only.
     */
    @PostMapping("/column-values/embed-all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> embedAllColumnValues() {
        try {
            int count = columnValueCollectionService.embedAllUnembedded();
            return ResponseEntity.ok(Map.of(
                "message", "Embedding complete",
                "embeddedCount", count
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error embedding column values", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain Insight Embedding (RAG) ====================

    /**
     * Embed all Brain insights for a connection into Azure AI Search.
     * This includes: plan patterns, workload insights, and cardinality recommendations.
     * Enables semantic RAG retrieval when users ask about optimization.
     */
    @PostMapping("/insights/{connectionId}/embed")
    public ResponseEntity<Map<String, Object>> embedBrainInsights(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Embedding Brain insights for connection: {}", connectionId);
            Map<String, Object> result = brainInsightEmbeddingService.embedAllInsights(connectionId);
            return ResponseEntity.ok(result);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error embedding Brain insights", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Embed plan patterns for a connection.
     * Only embeds reliable patterns (>= 5 uses, >= 60% effectiveness).
     */
    @PostMapping("/insights/{connectionId}/embed/patterns")
    public ResponseEntity<Map<String, Object>> embedPlanPatterns(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            int count = brainInsightEmbeddingService.embedPlanPatterns(connectionId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "patternsEmbedded", count,
                "connectionId", connectionId
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error embedding plan patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Embed workload profile and recommendations for a connection.
     */
    @PostMapping("/insights/{connectionId}/embed/workload")
    public ResponseEntity<Map<String, Object>> embedWorkloadInsight(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            boolean success = brainInsightEmbeddingService.embedWorkloadInsight(connectionId);
            return ResponseEntity.ok(Map.of(
                "success", success,
                "connectionId", connectionId
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error embedding workload insight", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Embed cardinality accuracy recommendations for a connection.
     */
    @PostMapping("/insights/{connectionId}/embed/cardinality")
    public ResponseEntity<Map<String, Object>> embedCardinalityInsights(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            int count = brainInsightEmbeddingService.embedCardinalityInsights(connectionId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "recommendationsEmbedded", count,
                "connectionId", connectionId
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error embedding cardinality insights", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain: Workload Intelligence Endpoints ====================

    /**
     * Collect workload metrics snapshot.
     */
    @PostMapping("/workload/collect/{connectionId}")
    public ResponseEntity<WorkloadMetricsSnapshot> collectWorkloadMetrics(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Collecting workload metrics for connection: {}", connectionId);
            WorkloadMetricsSnapshot snapshot = metricsCollectorService.collectMetrics(connectionId);
            return ResponseEntity.ok(snapshot);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error collecting workload metrics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Characterize workload for a connection.
     */
    @PostMapping("/workload/characterize/{connectionId}")
    public ResponseEntity<WorkloadProfile> characterizeWorkload(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Characterizing workload for connection: {}", connectionId);
            WorkloadProfile profile = workloadCharacterizationService.characterizeWorkload(connectionId);
            return ResponseEntity.ok(profile);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error characterizing workload", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get workload profile for a connection.
     */
    @GetMapping("/workload/profile/{connectionId}")
    public ResponseEntity<WorkloadProfile> getWorkloadProfile(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return workloadCharacterizationService.getProfile(connectionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching workload profile", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get workload learning status for a connection.
     * Returns snapshot count, readiness for characterization, and timing info.
     */
    @GetMapping("/workload/status/{connectionId}")
    public ResponseEntity<Map<String, Object>> getWorkloadStatus(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> status = new java.util.HashMap<>();

            // Snapshot count
            long snapshotCount = metricsCollectorService.getSnapshotCount(connectionId);
            status.put("snapshotCount", snapshotCount);
            status.put("minSnapshotsRequired", 10);
            status.put("readyToCharacterize", snapshotCount >= 10);

            // Last snapshot time
            var recentSnapshots = metricsCollectorService.getRecentSnapshots(connectionId, 1);
            if (!recentSnapshots.isEmpty()) {
                status.put("lastSnapshotAt", recentSnapshots.get(0).getCollectedAt());
                status.put("lastSnapshotMetricsCount", recentSnapshots.get(0).getMetricsCount());
            }

            // Profile status
            var profileOpt = workloadCharacterizationService.getProfile(connectionId);
            if (profileOpt.isPresent()) {
                var profile = profileOpt.get();
                status.put("hasProfile", true);
                status.put("profileLastUpdated", profile.getLastUpdatedAt() != null ?
                    profile.getLastUpdatedAt() : profile.getProfiledAt());
                status.put("workloadType", profile.getWorkloadType());
                status.put("confidence", profile.getClassificationConfidence());
            } else {
                status.put("hasProfile", false);
            }

            // Learning is automatic - show schedule info
            status.put("metricsCollectionInterval", "15 minutes");
            status.put("characterizationInterval", "4 hours");
            status.put("learningAutomatic", true);

            return ResponseEntity.ok(status);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching workload status", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Find similar workload profiles.
     */
    @GetMapping("/workload/similar/{connectionId}")
    public ResponseEntity<List<WorkloadProfile>> findSimilarWorkloads(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "5") int limit) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<WorkloadProfile> similar = workloadCharacterizationService.findSimilarProfiles(connectionId, limit);
            return ResponseEntity.ok(similar);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error finding similar workloads", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain 2.0: Config Tuning Endpoints ====================

    /**
     * Identify and rank important knobs.
     */
    @PostMapping("/config/knobs/{connectionId}")
    public ResponseEntity<List<KnobRanking>> identifyKnobs(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "LATENCY") KnobRanking.TargetMetric targetMetric) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Identifying knobs for connection: {} targeting: {}", connectionId, targetMetric);
            List<KnobRanking> rankings = knobIdentificationService.identifyKnobs(connectionId, targetMetric);
            return ResponseEntity.ok(rankings);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error identifying knobs", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get top ranked knobs.
     */
    @GetMapping("/config/top-knobs/{connectionId}")
    public ResponseEntity<List<KnobRanking>> getTopKnobs(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "LATENCY") KnobRanking.TargetMetric targetMetric,
            @RequestParam(defaultValue = "5") int limit) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<KnobRanking> topKnobs = knobIdentificationService.getTopKnobs(connectionId, targetMetric, limit);
            return ResponseEntity.ok(topKnobs);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching top knobs", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all knob rankings for a connection.
     */
    @GetMapping("/config/rankings/{connectionId}")
    public ResponseEntity<List<KnobRanking>> getAllKnobRankings(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<KnobRanking> rankings = knobIdentificationService.getAllRankings(connectionId);
            return ResponseEntity.ok(rankings);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching knob rankings", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Generate ML-based configuration recommendations.
     */
    @PostMapping("/config/recommendations/{connectionId}")
    public ResponseEntity<List<ConfigurationRecommendation>> generateConfigRecommendations(
            @PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Generating ML-based config recommendations for: {}", connectionId);
            List<ConfigurationRecommendation> recommendations = configTuningService.generateRecommendations(connectionId);
            return ResponseEntity.ok(recommendations);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating config recommendations", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Start a tuning experiment.
     */
    @PostMapping("/config/experiments/{connectionId}")
    public ResponseEntity<TuningExperiment> startTuningExperiment(
            @PathVariable String connectionId,
            @org.springframework.web.bind.annotation.RequestBody ExperimentRequest request) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Starting tuning experiment for: {}", connectionId);
            TuningExperiment experiment = configTuningService.startExperiment(
                connectionId, request.getKnobChanges(), request.getRecommendationId());
            return ResponseEntity.ok(experiment);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error starting tuning experiment", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Complete a tuning experiment.
     */
    @PostMapping("/config/experiments/{experimentId}/complete")
    public ResponseEntity<TuningExperiment> completeTuningExperiment(@PathVariable String experimentId) {
        try {
            accessControlService.assertCanManageConnectionContent(configTuningService.getConnectionId(experimentId));
            log.info("Completing experiment: {}", experimentId);
            TuningExperiment experiment = configTuningService.completeExperiment(experimentId);
            return ResponseEntity.ok(experiment);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error completing tuning experiment", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Cancel/delete a tuning experiment.
     */
    @DeleteMapping("/config/experiments/{experimentId}")
    public ResponseEntity<Void> cancelTuningExperiment(@PathVariable String experimentId) {
        try {
            accessControlService.assertCanManageConnectionContent(configTuningService.getConnectionId(experimentId));
            log.info("Cancelling experiment: {}", experimentId);
            configTuningService.cancelExperiment(experimentId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cancelling tuning experiment", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get experiment history.
     */
    @GetMapping("/config/experiments/{connectionId}")
    public ResponseEntity<List<TuningExperiment>> getExperimentHistory(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<TuningExperiment> history = configTuningService.getExperimentHistory(connectionId, limit);
            return ResponseEntity.ok(history);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching experiment history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get experiment success rate.
     */
    @GetMapping("/config/experiments/{connectionId}/success-rate")
    public ResponseEntity<Map<String, Object>> getExperimentSuccessRate(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            double successRate = configTuningService.getExperimentSuccessRate(connectionId);
            return ResponseEntity.ok(Map.of(
                "connectionId", connectionId,
                "successRate", successRate
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching experiment success rate", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain 2.0: Column Statistics Endpoints ====================

    /**
     * Collect column statistics for a table.
     */
    @PostMapping("/statistics/{connectionId}/tables/{tableName}")
    public ResponseEntity<List<ColumnStatistics>> collectTableStatistics(
            @PathVariable String connectionId,
            @PathVariable String tableName) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Collecting statistics for table: {} in connection: {}", tableName, connectionId);
            List<ColumnStatistics> stats = cardinalityEstimationService.collectTableStatistics(connectionId, tableName);
            return ResponseEntity.ok(stats);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error collecting table statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get column statistics for a connection.
     */
    @GetMapping("/statistics/{connectionId}")
    public ResponseEntity<List<ColumnStatistics>> getColumnStatistics(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<ColumnStatistics> stats = cardinalityEstimationService.getStatistics(connectionId);
            return ResponseEntity.ok(stats);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching column statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get high cardinality columns.
     */
    @GetMapping("/statistics/{connectionId}/high-cardinality")
    public ResponseEntity<List<ColumnStatistics>> getHighCardinalityColumns(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "1000") long minDistinct) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<ColumnStatistics> columns = cardinalityEstimationService.getHighCardinalityColumns(connectionId, minDistinct);
            return ResponseEntity.ok(columns);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching high cardinality columns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get skewed columns.
     */
    @GetMapping("/statistics/{connectionId}/skewed")
    public ResponseEntity<List<ColumnStatistics>> getSkewedColumns(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<ColumnStatistics> columns = cardinalityEstimationService.getSkewedColumns(connectionId);
            return ResponseEntity.ok(columns);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching skewed columns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Estimate cardinality for a predicate.
     */
    @PostMapping("/statistics/{connectionId}/estimate")
    public ResponseEntity<Map<String, Object>> estimateCardinality(
            @PathVariable String connectionId,
            @org.springframework.web.bind.annotation.RequestBody CardinalityEstimateRequest request) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            long estimate;
            if (request.getLowBound() != null || request.getHighBound() != null) {
                estimate = cardinalityEstimationService.estimateRange(
                    connectionId, request.getTableName(), request.getColumnName(),
                    request.getLowBound(), request.getHighBound());
            } else {
                estimate = cardinalityEstimationService.estimateEquality(
                    connectionId, request.getTableName(), request.getColumnName(), request.getValue());
            }

            return ResponseEntity.ok(Map.of(
                "estimate", estimate,
                "tableName", request.getTableName(),
                "columnName", request.getColumnName()
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error estimating cardinality", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Refresh stale statistics.
     */
    @PostMapping("/statistics/{connectionId}/refresh")
    public ResponseEntity<Map<String, Object>> refreshStaleStatistics(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            int refreshed = cardinalityEstimationService.refreshStaleStatistics(connectionId);
            return ResponseEntity.ok(Map.of("refreshedCount", refreshed));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error refreshing statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get cardinality estimation accuracy statistics.
     * Compares estimated vs actual row counts from recorded query executions.
     */
    @GetMapping("/statistics/{connectionId}/accuracy")
    public ResponseEntity<Map<String, Object>> getCardinalityAccuracy(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> accuracy = adaptivePlanScoringService.getCardinalityAccuracyStats(connectionId);
            return ResponseEntity.ok(accuracy);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching cardinality accuracy", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain 2.0: Adaptive Plan Scoring Endpoints ====================

    /**
     * Record a query execution for learning.
     */
    @PostMapping("/executions/{connectionId}")
    public ResponseEntity<PlanExecution> recordExecution(
            @PathVariable String connectionId,
            @org.springframework.web.bind.annotation.RequestBody ExecutionRecordRequest request) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            PlanExecution execution = adaptivePlanScoringService.recordExecution(
                connectionId, request.getQuery(), request.getActualExecutionMs(), request.getActualRows());
            return ResponseEntity.ok(execution);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error recording execution", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get recent executions.
     */
    @GetMapping("/executions/{connectionId}")
    public ResponseEntity<List<PlanExecution>> getRecentExecutions(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<PlanExecution> executions = adaptivePlanScoringService.getRecentExecutions(connectionId, limit);
            return ResponseEntity.ok(executions);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching recent executions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get executions with significant cardinality errors.
     */
    @GetMapping("/executions/{connectionId}/cardinality-errors")
    public ResponseEntity<List<PlanExecution>> getCardinalityErrors(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<PlanExecution> errors = adaptivePlanScoringService.getSignificantCardinalityErrors(connectionId, limit);
            return ResponseEntity.ok(errors);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching cardinality errors", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get cost calibration status.
     */
    @GetMapping("/calibration/{connectionId}")
    public ResponseEntity<Map<String, Object>> getCalibrationStatus(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> status = adaptivePlanScoringService.getCalibrationStatus(connectionId);
            return ResponseEntity.ok(status);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching calibration status", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get queries with multiple execution plans.
     */
    @GetMapping("/executions/{connectionId}/multiple-plans")
    public ResponseEntity<Map<String, List<String>>> getQueriesWithMultiplePlans(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, List<String>> result = adaptivePlanScoringService.findQueriesWithMultiplePlans(connectionId);
            return ResponseEntity.ok(result);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching queries with multiple plans", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Reset cost calibration.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/calibration/{connectionId}")
    public ResponseEntity<Void> resetCalibration(@PathVariable String connectionId) {
        try {
            adaptivePlanScoringService.resetCalibration(connectionId);
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error resetting calibration", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain 2.0: Plan Pattern Library Endpoints ====================

    /**
     * Get suggestions for a query.
     */
    @PostMapping("/patterns/{connectionId}/suggestions")
    public ResponseEntity<List<Map<String, Object>>> getPatternSuggestions(
            @PathVariable String connectionId,
            @org.springframework.web.bind.annotation.RequestBody QueryRequest request) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            List<Map<String, Object>> suggestions = planPatternLibraryService.getSuggestions(connectionId, request.getQuery());
            return ResponseEntity.ok(suggestions);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting pattern suggestions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get reliable patterns.
     */
    @GetMapping("/patterns/{connectionId}/reliable")
    public ResponseEntity<List<PlanPattern>> getReliablePatterns(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<PlanPattern> patterns = planPatternLibraryService.getReliablePatterns(connectionId);
            return ResponseEntity.ok(patterns);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching reliable patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get most used patterns.
     */
    @GetMapping("/patterns/{connectionId}/most-used")
    public ResponseEntity<List<PlanPattern>> getMostUsedPatterns(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<PlanPattern> patterns = planPatternLibraryService.getMostUsedPatterns(connectionId, limit);
            return ResponseEntity.ok(patterns);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching most used patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get patterns with optimizations.
     */
    @GetMapping("/patterns/{connectionId}/with-optimizations")
    public ResponseEntity<List<PlanPattern>> getPatternsWithOptimizations(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<PlanPattern> patterns = planPatternLibraryService.getPatternsWithOptimizations(connectionId);
            return ResponseEntity.ok(patterns);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching patterns with optimizations", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get pattern statistics.
     */
    @GetMapping("/patterns/{connectionId}/stats")
    public ResponseEntity<Map<String, Object>> getPatternStatistics(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> stats = planPatternLibraryService.getPatternStatistics(connectionId);
            return ResponseEntity.ok(stats);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching pattern statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Record pattern feedback.
     */
    @PostMapping("/patterns/{patternId}/feedback")
    public ResponseEntity<Void> recordPatternFeedback(
            @PathVariable String patternId,
            @RequestParam boolean wasSuccessful) {
        try {
            accessControlService.assertCanManageConnectionContent(planPatternLibraryService.getConnectionId(patternId));
            planPatternLibraryService.recordFeedback(patternId, wasSuccessful);
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error recording pattern feedback", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Cleanup ineffective patterns.
     */
    @PostMapping("/patterns/{connectionId}/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupPatterns(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            int deleted = planPatternLibraryService.cleanupIneffectivePatterns(connectionId);
            return ResponseEntity.ok(Map.of("deletedCount", deleted));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cleaning up patterns", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain 2.0: ML Overview Endpoint ====================

    /**
     * Get ML-based Brain 2.0 overview for a connection.
     */
    @GetMapping("/ml-overview/{connectionId}")
    public ResponseEntity<Map<String, Object>> getMlOverview(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> overview = new java.util.HashMap<>();

            // Workload Profile
            workloadCharacterizationService.getProfile(connectionId).ifPresent(profile -> {
                Map<String, Object> workload = new java.util.HashMap<>();
                workload.put("type", profile.getWorkloadType());
                workload.put("subtype", profile.getWorkloadSubtype());
                workload.put("confidence", profile.getClassificationConfidence());
                workload.put("lastUpdated", profile.getLastUpdatedAt());
                overview.put("workloadProfile", workload);
            });

            // Config Tuning Status
            List<KnobRanking> rankings = knobIdentificationService.getAllRankings(connectionId);
            Map<String, Object> configStatus = new java.util.HashMap<>();
            configStatus.put("knobsRanked", rankings.size());
            configStatus.put("experimentSuccessRate", configTuningService.getExperimentSuccessRate(connectionId));
            overview.put("configTuning", configStatus);

            // Query Intelligence Status
            Map<String, Object> queryIntel = new java.util.HashMap<>();
            queryIntel.put("statisticsCount", cardinalityEstimationService.getStatistics(connectionId).size());
            queryIntel.put("calibrationStatus", adaptivePlanScoringService.getCalibrationStatus(connectionId));
            queryIntel.put("patternStats", planPatternLibraryService.getPatternStatistics(connectionId));
            overview.put("queryIntelligence", queryIntel);

            return ResponseEntity.ok(overview);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching ML overview", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Clear all execution data for a connection.
     * Use before re-backfilling to get fresh data with original (non-normalized) queries.
     */
    @DeleteMapping("/query-intelligence/{connectionId}/executions")
    public ResponseEntity<Map<String, Object>> clearExecutions(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            long countBefore = adaptivePlanScoringService.getRecentExecutions(connectionId, 1).isEmpty() ? 0 :
                adaptivePlanScoringService.getCardinalityAccuracyStats(connectionId).get("totalExecutions") instanceof Number n ? n.longValue() : 0;
            adaptivePlanScoringService.clearExecutions(connectionId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "deletedCount", countBefore,
                "message", "Cleared " + countBefore + " executions. Run /backfill to repopulate with original queries."
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error clearing executions", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Backfill Query Intelligence from existing slow query history.
     * This populates cardinality accuracy and plan pattern data from historical slow queries.
     * Processes one history at a time to avoid OOM with large analysis data.
     */
    @PostMapping("/query-intelligence/{connectionId}/backfill")
    public ResponseEntity<Map<String, Object>> backfillQueryIntelligence(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Starting Query Intelligence backfill for connection {}", connectionId);

            // Get history summaries (lightweight) instead of full entities to avoid OOM
            var summaries = slowQueryHistoryService.getRecentHistorySummaries(connectionId);

            int totalProcessed = 0;
            int historiesProcessed = 0;
            final int MAX_HISTORIES = 10; // Limit to prevent long-running request

            for (int i = 0; i < Math.min(summaries.size(), MAX_HISTORIES); i++) {
                var summary = summaries.get(i);
                try {
                    // Load one history at a time to avoid OOM
                    var historyOpt = slowQueryHistoryService.getById(summary.getId());
                    if (historyOpt.isPresent()) {
                        SlowQueryAnalysis analysis = slowQueryHistoryService.getAnalysisData(historyOpt.get());
                        if (analysis != null && analysis.getTopSlowQueries() != null) {
                            int processed = adaptivePlanScoringService.backfillFromSlowQueries(
                                connectionId, analysis.getTopSlowQueries());
                            totalProcessed += processed;
                            historiesProcessed++;
                        }
                    }
                } catch (ResponseStatusException e) {
                    throw e;
                } catch (Exception e) {
                    log.debug("Could not process history {}: {}", summary.getId(), e.getMessage());
                }
            }

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("success", true);
            result.put("historiesProcessed", historiesProcessed);
            result.put("executionsRecorded", totalProcessed);
            result.put("message", String.format("Backfilled %d executions from %d history entries",
                totalProcessed, historiesProcessed));

            // Also return updated accuracy stats
            result.put("accuracy", adaptivePlanScoringService.getCardinalityAccuracyStats(connectionId));

            log.info("Query Intelligence backfill completed: {} executions from {} histories",
                totalProcessed, historiesProcessed);

            return ResponseEntity.ok(result);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error backfilling Query Intelligence", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Start background EXPLAIN processing to populate cardinality data.
     * This runs EXPLAIN on tracked queries to get optimizer's estimated rows.
     */
    @PostMapping("/query-intelligence/{connectionId}/explain-backfill")
    public ResponseEntity<AdaptivePlanScoringService.ExplainJobProgress> startExplainBackfill(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "500") int maxQueries) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            // Limit max queries to prevent runaway jobs
            int safeMax = Math.min(maxQueries, 2000);
            var progress = adaptivePlanScoringService.startExplainBackfill(connectionId, safeMax);
            return ResponseEntity.ok(progress);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error starting EXPLAIN backfill", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get progress of EXPLAIN backfill job.
     */
    @GetMapping("/query-intelligence/{connectionId}/explain-progress")
    public ResponseEntity<AdaptivePlanScoringService.ExplainJobProgress> getExplainProgress(
            @PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            var progress = adaptivePlanScoringService.getExplainProgress(connectionId);
            return ResponseEntity.ok(progress);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting EXPLAIN progress", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Cancel a running EXPLAIN backfill job.
     */
    @PostMapping("/query-intelligence/{connectionId}/explain-cancel")
    public ResponseEntity<Void> cancelExplainBackfill(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            adaptivePlanScoringService.cancelExplainBackfill(connectionId);
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cancelling EXPLAIN backfill", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Brain 2.0: Request DTOs ====================

    @lombok.Data
    public static class ExperimentRequest {
        private Map<String, Map<String, String>> knobChanges;
        private String recommendationId;
    }

    @lombok.Data
    public static class CardinalityEstimateRequest {
        private String tableName;
        private String columnName;
        private String value;
        private String lowBound;
        private String highBound;
    }

    @lombok.Data
    public static class ExecutionRecordRequest {
        private String query;
        private Double actualExecutionMs;
        private Long actualRows;
    }

    @lombok.Data
    public static class QueryRequest {
        private String query;
    }
}
