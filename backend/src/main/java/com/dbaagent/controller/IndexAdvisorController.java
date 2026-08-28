package com.dbaagent.controller;

import com.dbaagent.service.IndexAdvisorService;
import com.dbaagent.service.PerformanceMonitoringService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for enhanced index advisor functionality
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/index-advisor")
@RequiredArgsConstructor
@Slf4j
public class IndexAdvisorController {

    private final IndexAdvisorService indexAdvisorService;
    private final PerformanceMonitoringService performanceMonitoringService;
    private final AccessControlService accessControlService;

    /**
     * Get comprehensive index health report
     */
    @GetMapping("/{connectionId}/health-report")
    public ResponseEntity<Map<String, Object>> getHealthReport(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(indexAdvisorService.getIndexHealthReport(connectionId));
    }

    /**
     * Get unused indexes
     */
    @GetMapping("/{connectionId}/unused")
    public ResponseEntity<List<Map<String, Object>>> getUnusedIndexes(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(performanceMonitoringService.getUnusedIndexes(connectionId));
    }

    /**
     * Get duplicate/redundant indexes
     */
    @GetMapping("/{connectionId}/duplicates")
    public ResponseEntity<List<Map<String, Object>>> getDuplicateIndexes(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(performanceMonitoringService.getDuplicateIndexes(connectionId));
    }

    /**
     * Estimate index creation impact
     */
    @PostMapping("/{connectionId}/estimate-create")
    public ResponseEntity<Map<String, Object>> estimateIndexCreation(
            @PathVariable String connectionId,
            @RequestBody Map<String, Object> request) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        String tableName = (String) request.get("tableName");
        @SuppressWarnings("unchecked")
        List<String> columnsList = (List<String>) request.get("columns");
        String[] columns = columnsList != null ? columnsList.toArray(new String[0]) : new String[0];
        boolean unique = Boolean.TRUE.equals(request.get("unique"));

        if (tableName == null || columns.length == 0) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(indexAdvisorService.estimateIndexCreationImpact(
                connectionId, tableName, columns, unique));
    }

    /**
     * Estimate index drop impact
     */
    @PostMapping("/{connectionId}/estimate-drop")
    public ResponseEntity<Map<String, Object>> estimateIndexDrop(
            @PathVariable String connectionId,
            @RequestBody Map<String, Object> request) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        String tableName = (String) request.get("tableName");
        String indexName = (String) request.get("indexName");

        if (tableName == null || indexName == null) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(indexAdvisorService.estimateIndexDropImpact(
                connectionId, tableName, indexName));
    }

    /**
     * Get index usage statistics for a table
     */
    @GetMapping("/{connectionId}/usage/{tableName}")
    public ResponseEntity<List<Map<String, Object>>> getIndexUsageStats(
            @PathVariable String connectionId,
            @PathVariable String tableName) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(performanceMonitoringService.getIndexUsageStats(connectionId, tableName));
    }

    /**
     * Get cache hit ratios (includes index cache)
     */
    @GetMapping("/{connectionId}/cache-stats")
    public ResponseEntity<Map<String, Double>> getCacheStats(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(performanceMonitoringService.getCacheHitRatios(connectionId));
    }
}
