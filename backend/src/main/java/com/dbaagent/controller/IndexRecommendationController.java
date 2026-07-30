package com.dbaagent.controller;

import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.IndexRecommendationEvidence;
import com.dbaagent.service.IndexRecommendationApplyService;
import com.dbaagent.service.IndexRecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for index recommendation endpoints
 */
@RestController
@RequestMapping("/index-recommendations")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:3000")
public class IndexRecommendationController {

    private final IndexRecommendationService recommendationService;
    private final IndexRecommendationApplyService applyService;

    /**
     * Generate new index recommendations based on query history
     */
    @PostMapping("/generate/{connectionId}")
    public ResponseEntity<GenerateResponse> generateRecommendations(@PathVariable String connectionId) {
        log.info("Generating index recommendations for connection: {}", connectionId);

        try {
            List<IndexRecommendationEntity> recommendations = recommendationService.generateRecommendations(connectionId);

            GenerateResponse response = new GenerateResponse();
            response.setSuccess(true);
            response.setMessage("Generated " + recommendations.size() + " index recommendations");
            response.setCount(recommendations.size());
            response.setRecommendations(recommendations);

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating recommendations: {}", e.getMessage(), e);

            GenerateResponse response = new GenerateResponse();
            response.setSuccess(false);
            response.setMessage("Error generating recommendations: " + e.getMessage());
            response.setCount(0);

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get all recommendations for a connection
     */
    @GetMapping("/{connectionId}")
    public ResponseEntity<List<IndexRecommendationEntity>> getRecommendations(@PathVariable String connectionId) {
        log.info("Fetching all recommendations for connection: {}", connectionId);

        List<IndexRecommendationEntity> recommendations = recommendationService.getRecommendations(connectionId);
        return ResponseEntity.ok(recommendations);
    }

    /**
     * Get pending recommendations for a connection
     */
    @GetMapping("/pending/{connectionId}")
    public ResponseEntity<List<IndexRecommendationEntity>> getPendingRecommendations(@PathVariable String connectionId) {
        log.info("Fetching pending recommendations for connection: {}", connectionId);

        List<IndexRecommendationEntity> recommendations = recommendationService.getPendingRecommendations(connectionId);
        return ResponseEntity.ok(recommendations);
    }

    /**
     * Pre-computed top-N pending recommendations ranked by priority, net
     * benefit (workload − write cost), recurrence, and recency. Backs the
     * DeepSQL MCP {@code get_index_recommendations} tool — no live scan,
     * just whatever the periodic refresh has already accumulated.
     *
     * Each entry includes the top contributing queries ({@code topEvidence})
     * so a caller can see *why* the recommendation exists rather than trust
     * a heuristic score blindly.
     */
    @GetMapping("/{connectionId}/top")
    public ResponseEntity<List<TopRecommendationResponse>> getTopRecommendations(
        @PathVariable String connectionId,
        @RequestParam(value = "limit", required = false, defaultValue = "5") int limit
    ) {
        log.info("Fetching top {} recommendations for connection: {}", limit, connectionId);
        List<TopRecommendationResponse> body = recommendationService
            .getTopRecommendationsWithEvidence(connectionId, limit)
            .stream()
            .map(TopRecommendationResponse::from)
            .toList();
        return ResponseEntity.ok(body);
    }

    /**
     * Mark recommendation as applied
     */
    @PutMapping("/{id}/apply")
    public ResponseEntity<IndexRecommendationEntity> markAsApplied(@PathVariable String id) {
        log.info("Marking recommendation as applied: {}", id);

        try {
            IndexRecommendationEntity recommendation = recommendationService.markAsApplied(id);
            return ResponseEntity.ok(recommendation);
        } catch (IllegalArgumentException e) {
            log.error("Recommendation not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error marking recommendation as applied: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Dismiss recommendation
     */
    @PutMapping("/{id}/dismiss")
    public ResponseEntity<IndexRecommendationEntity> dismissRecommendation(@PathVariable String id) {
        log.info("Dismissing recommendation: {}", id);

        try {
            IndexRecommendationEntity recommendation = recommendationService.dismissRecommendation(id);
            return ResponseEntity.ok(recommendation);
        } catch (IllegalArgumentException e) {
            log.error("Recommendation not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error dismissing recommendation: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Apply (or dry-run) a recommendation against its target connection and
     * measure the before/after benefit on contributing queries.
     *
     * Modes:
     *   DRY_RUN (default) — no writes. HypoPG virtual index + EXPLAIN cost
     *     diff. Postgres-only; returns FAILED if HypoPG isn't installed.
     *   APPLY — real CREATE/DROP INDEX CONCURRENTLY (Postgres) / plain (MySQL).
     *     Requires {@code confirm=true} as a write guard.
     *   APPLY_AND_MEASURE — APPLY plus {@code EXPLAIN ANALYZE} before+after so
     *     wall-clock times are recorded. Slowest mode; runs the queries.
     *
     * Response includes per-sample timings so the caller can audit each
     * contributing query, not just the aggregate.
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<IndexRecommendationApplyService.ApplyResult> applyRecommendation(
        @PathVariable String id,
        @RequestParam(value = "mode", required = false, defaultValue = "DRY_RUN") String mode,
        @RequestParam(value = "confirm", required = false, defaultValue = "false") boolean confirm,
        @RequestParam(value = "concurrent", required = false, defaultValue = "true") boolean concurrent
    ) {
        IndexRecommendationApplyService.Mode m;
        try {
            m = IndexRecommendationApplyService.Mode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        IndexRecommendationApplyService.ApplyOptions opts = new IndexRecommendationApplyService.ApplyOptions(concurrent);
        IndexRecommendationApplyService.ApplyResult result = applyService.apply(id, m, confirm, opts);
        return ResponseEntity.ok(result);
    }

    /**
     * Delete recommendation
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteRecommendation(@PathVariable String id) {
        log.info("Deleting recommendation: {}", id);

        try {
            recommendationService.deleteRecommendation(id);
            return ResponseEntity.ok(Map.of(
                "success", "true",
                "message", "Recommendation deleted successfully"
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting recommendation: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", "false",
                "message", "Error deleting recommendation: " + e.getMessage()
            ));
        }
    }

    // DTOs
    public static class GenerateResponse {
        private boolean success;
        private String message;
        private int count;
        private List<IndexRecommendationEntity> recommendations;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public List<IndexRecommendationEntity> getRecommendations() {
            return recommendations;
        }

        public void setRecommendations(List<IndexRecommendationEntity> recommendations) {
            this.recommendations = recommendations;
        }
    }

    /**
     * Wire shape returned by {@link #getTopRecommendations}. Flattens the
     * recommendation row and attaches its top contributing queries from the
     * V96 evidence table so callers can see *why* each suggestion exists.
     *
     * Mirrors the shape pganalyze and Dexter expose — the recommendation
     * carries a justification payload, not just the DDL string.
     */
    public record TopRecommendationResponse(
        String id,
        String tableName,
        String columnNames,
        String indexName,
        String createStatement,
        String kind,
        String priority,
        Integer occurrenceCount,
        Integer estimatedImpact,
        Long workloadScoreMs,
        Long writeCostScore,
        Long netBenefitMs,
        Integer evidenceCount,
        Integer affectedQueries,
        String reason,
        java.time.LocalDateTime firstSeenAt,
        java.time.LocalDateTime lastSeenAt,
        List<EvidencePayload> topEvidence
    ) {
        public static TopRecommendationResponse from(IndexRecommendationService.TopRecommendationWithEvidence p) {
            IndexRecommendationEntity rec = p.recommendation();
            List<EvidencePayload> ev = p.topEvidence() == null ? List.of()
                : p.topEvidence().stream().map(EvidencePayload::from).toList();
            return new TopRecommendationResponse(
                rec.getId(),
                rec.getTableName(),
                rec.getColumnNames(),
                rec.getIndexName(),
                rec.getCreateStatement(),
                rec.getKind() == null ? null : rec.getKind().name(),
                rec.getPriority() == null ? null : rec.getPriority().name(),
                rec.getOccurrenceCount(),
                rec.getEstimatedImpact(),
                rec.getWorkloadScoreMs(),
                rec.getWriteCostScore(),
                rec.netBenefitMs(),
                rec.getEvidenceCount(),
                rec.getAffectedQueries(),
                rec.getReason(),
                rec.getFirstSeenAt(),
                rec.getLastSeenAt(),
                ev
            );
        }
    }

    /** One contributing query, surfaced verbatim from the V96 evidence table. */
    public record EvidencePayload(
        String fingerprint,
        Long calls,
        Double meanExecTimeMs,
        Double totalExecTimeMs,
        Long rowsExamined,
        String role,
        String exampleSql
    ) {
        public static EvidencePayload from(IndexRecommendationEvidence e) {
            return new EvidencePayload(
                e.getQueryFingerprint(),
                e.getCalls(),
                e.getMeanExecTimeMs(),
                e.getTotalExecTimeMs(),
                e.getRowsExamined(),
                e.getRole(),
                e.getExampleSql()
            );
        }
    }
}
