package com.dbaagent.controller;

import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.TrainingJobHistory;
import com.dbaagent.model.TrainingJobStatus;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.service.ChatService;
import com.dbaagent.service.IndexBackfillService;
import com.dbaagent.service.RetrievedContextResult;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.TrainingJobService;
import com.dbaagent.service.TrainingService;
import com.dbaagent.service.UserDataAccessPolicyService;
import com.dbaagent.service.security.AccessControlService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/training")
@RequiredArgsConstructor
@Slf4j
public class TrainingController {

    private final TrainingService trainingService;
    private final TrainingJobService trainingJobService;
    private final ChatService chatService;
    private final IndexBackfillService indexBackfillService;
    private final CredentialRepository credentialRepository;
    private final SemanticModelService semanticModelService;
    private final AccessControlService accessControlService;
    private final UserDataAccessPolicyService userDataAccessPolicyService;

    /**
     * Train with schema DDL
     */
    @PostMapping("/schema/{connectionId}")
    public ResponseEntity<TrainingJobStatus> trainWithSchema(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            TrainingJobStatus status = trainingJobService.startSchemaTraining(connectionId);
            return ResponseEntity.ok(status);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error training with schema", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/schema/status/{connectionId}")
    public ResponseEntity<TrainingJobStatus> getSchemaTrainingStatus(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(trainingJobService.getSchemaTrainingStatus(connectionId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching training status", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/schema/cancel/{connectionId}")
    public ResponseEntity<TrainingJobStatus> cancelSchemaTraining(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            return ResponseEntity.ok(trainingJobService.cancelSchemaTraining(connectionId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cancelling training", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping(value = "/schema/stream/{connectionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSchemaTraining(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return trainingJobService.subscribeSchemaTraining(connectionId);
    }

    @GetMapping("/history/{connectionId}")
    public ResponseEntity<List<TrainingJobHistory>> getTrainingHistory(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "5") int limit
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(trainingJobService.getTrainingHistory(connectionId, limit));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching training history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/queue/metrics")
    public ResponseEntity<Map<String, Object>> getQueueMetrics() {
        try {
            return ResponseEntity.ok(trainingJobService.getQueueMetrics());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching training queue metrics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Add documentation for table, column, or business term
     */
    @PostMapping("/documentation")
    public ResponseEntity<SchemaDocumentation> addDocumentation(
        @RequestBody DocumentationRequest request
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(request.getConnectionId());
            SchemaDocumentation doc = trainingService.trainWithDocumentation(
                request.getConnectionId(),
                request.getObjectType(),
                request.getObjectName(),
                request.getParentObject(),
                request.getDescription(),
                request.getBusinessTerms(),
                request.getExamples(),
                request.getCreatedBy()
            );
            return ResponseEntity.ok(doc);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error adding documentation", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get training statistics
     */
    @GetMapping("/stats/{connectionId}")
    public ResponseEntity<Map<String, Object>> getStats(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> stats = trainingService.getTrainingStats(connectionId);
            return ResponseEntity.ok(stats);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting training stats", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Debug retrieval for a natural-language query.
     * Useful for RAG diagnostics (table coverage, type mix, and relevance ranking).
     */
    @GetMapping("/retrieve/{connectionId}")
    public ResponseEntity<Map<String, Object>> debugRetrieve(
        @PathVariable String connectionId,
        @RequestParam("q") String question,
        @RequestParam(defaultValue = "20") int topK
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            if (question == null || question.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Query parameter 'q' is required"));
            }
            Map<String, Object> payload = trainingService.debugRetrieve(connectionId, question, topK);
            filterDebugRetrieval(connectionId, payload);
            return ResponseEntity.ok(payload);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error debugging retrieval", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Debug training context construction for a question.
     */
    @PostMapping("/context/{connectionId}")
    public ResponseEntity<RetrievedContextResult> debugTrainingContext(
        @PathVariable String connectionId,
        @RequestBody DebugTrainingContextRequest request
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(chatService.debugRagContext(connectionId, request.getQuestion()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error debugging training context", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Clear training cache
     */
    @DeleteMapping("/cache/{connectionId}")
    public ResponseEntity<Map<String, String>> clearCache(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            trainingService.clearCache(connectionId);
            return ResponseEntity.ok(Map.of(
                "message", "Cache cleared successfully",
                "connectionId", connectionId
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error clearing cache", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reject a query example by ID (removes from RAG retrieval).
     */
    @DeleteMapping("/query-example/{exampleId}")
    public ResponseEntity<Map<String, Object>> rejectQueryExample(
        @PathVariable String exampleId
    ) {
        accessControlService.assertCanManageConnectionContent(trainingService.getQueryExampleConnectionId(exampleId));
        boolean rejected = trainingService.rejectQueryExampleById(exampleId);
        return ResponseEntity.ok(Map.of(
            "exampleId", exampleId,
            "rejected", rejected
        ));
    }

    /**
     * Start v1→v2 index backfill. Creates v2 index, enriches all docs with tableName,
     * runs parity check, and activates v2 (enabling Stage 2 retrieval).
     * Runs in the background; poll status via GET /training/backfill-v2/status.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/backfill-v2")
    public ResponseEntity<IndexBackfillService.BackfillProgress> startBackfill() {
        try {
            return ResponseEntity.ok(indexBackfillService.startBackfill());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error starting backfill", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/backfill-v2/status")
    public ResponseEntity<IndexBackfillService.BackfillProgress> getBackfillStatus() {
        return ResponseEntity.ok(indexBackfillService.getProgress());
    }

    /**
     * Re-index a single connection: clears existing docs and re-trains from current schema.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{connectionId}/reindex")
    public ResponseEntity<Map<String, Object>> reindexConnection(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            Thread.startVirtualThread(() -> rebuildAndReindexConnection(connectionId));
            return ResponseEntity.ok(Map.of(
                "connectionId", connectionId,
                "message", "Reindex started"
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error starting reindex for connection {}", connectionId, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Re-index all connections. Runs in the background on virtual threads.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reindex-all")
    public ResponseEntity<Map<String, Object>> reindexAll() {
        try {
            List<String> connectionIds = credentialRepository.findAllIds();
            Semaphore semaphore = new Semaphore(5);

            for (String connectionId : connectionIds) {
                Thread.startVirtualThread(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            rebuildAndReindexConnection(connectionId);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Reindex interrupted for connection {}", connectionId, e);
                    }
                });
            }
            return ResponseEntity.ok(Map.of(
                "message", "Reindex started for all connections",
                "connectionCount", connectionIds.size()
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error starting reindex-all", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    private void rebuildAndReindexConnection(String connectionId) {
        semanticModelService.rebuildSemanticModel(connectionId);
        trainingService.reindexConnection(connectionId);
    }

    @SuppressWarnings("unchecked")
    private void filterDebugRetrieval(String connectionId, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        Object results = payload.get("results");
        if (!(results instanceof List<?> rows)) {
            return;
        }
        List<Map<String, Object>> filtered = new java.util.ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Object metadata = map.get("metadata");
            if (userDataAccessPolicyService.isRagMetadataInScope(
                connectionId,
                accessControlService.getCurrentUsername(),
                accessControlService.isCurrentUserAdmin(),
                metadata == null ? null : String.valueOf(metadata)
            )) {
                filtered.add((Map<String, Object>) map);
            }
        }
        payload.put("results", filtered);
        payload.put("resultCount", filtered.size());
    }

    @Data
    public static class DocumentationRequest {
        private String connectionId;
        private SchemaDocumentation.DocumentationType objectType;
        private String objectName;
        private String parentObject;
        private String description;
        private String businessTerms;
        private String examples;
        private String createdBy;
    }

    @Data
    public static class DebugTrainingContextRequest {
        private String question;
    }
}
