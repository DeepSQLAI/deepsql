package com.dbaagent.controller;

import com.dbaagent.model.IndexRecommendation;
import com.dbaagent.model.PerformanceAnalysis;
import com.dbaagent.service.DatabaseAdvisorService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for the performance advisor (analysis, missing indexes, health summary).
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/advisor")
@RequiredArgsConstructor
@Slf4j
public class AdvisorController {

    private final DatabaseAdvisorService advisorService;
    private final AccessControlService accessControlService;

    /**
     * Get comprehensive performance analysis
     */
    @GetMapping("/analyze/{connectionId}")
    public ResponseEntity<PerformanceAnalysis> analyzePerformance(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Performance analysis requested for connection: {}", connectionId);
            PerformanceAnalysis analysis = advisorService.analyzePerformance(connectionId);
            return ResponseEntity.ok(analysis);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing performance", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get missing index recommendations only
     */
    @GetMapping("/indexes/{connectionId}")
    public ResponseEntity<List<IndexRecommendation>> getMissingIndexes(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Index recommendations requested for connection: {}", connectionId);
            PerformanceAnalysis analysis = advisorService.analyzePerformance(connectionId);
            return ResponseEntity.ok(analysis.getIndexRecommendations());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting index recommendations", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get health summary
     */
    @GetMapping("/health/{connectionId}")
    public ResponseEntity<HealthSummary> getHealthSummary(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Health summary requested for connection: {}", connectionId);
            PerformanceAnalysis analysis = advisorService.analyzePerformance(connectionId);

            HealthSummary summary = HealthSummary.builder()
                .overallHealth(analysis.getOverallHealth())
                .totalRecommendations(
                    analysis.getIndexRecommendations().size() +
                    analysis.getGeneralRecommendations().size()
                )
                .criticalIssues((int) analysis.getIndexRecommendations().stream()
                    .filter(r -> r.getPriority() == IndexRecommendation.RecommendationPriority.CRITICAL)
                    .count() +
                    (int) analysis.getGeneralRecommendations().stream()
                    .filter(r -> r.getPriority() == com.dbaagent.model.DatabaseRecommendation.RecommendationPriority.CRITICAL)
                    .count())
                .highPriorityIssues((int) analysis.getIndexRecommendations().stream()
                    .filter(r -> r.getPriority() == IndexRecommendation.RecommendationPriority.HIGH)
                    .count() +
                    (int) analysis.getGeneralRecommendations().stream()
                    .filter(r -> r.getPriority() == com.dbaagent.model.DatabaseRecommendation.RecommendationPriority.HIGH)
                    .count())
                .aiSummary(analysis.getAiSummary())
                .build();

            return ResponseEntity.ok(summary);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting health summary", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HealthSummary {
        private PerformanceAnalysis.OverallHealth overallHealth;
        private Integer totalRecommendations;
        private Integer criticalIssues;
        private Integer highPriorityIssues;
        private String aiSummary;
    }
}
