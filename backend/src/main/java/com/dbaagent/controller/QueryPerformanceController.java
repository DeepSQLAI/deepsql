package com.dbaagent.controller;

import com.dbaagent.model.QueryPerformanceHistory;
import com.dbaagent.model.QueryPerformanceRegression;
import com.dbaagent.service.QueryPerformanceService;
import com.dbaagent.service.security.AccessControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for per-query performance history, trends, and regressions.
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/query-performance")
@Slf4j
public class QueryPerformanceController {

    @Autowired
    private QueryPerformanceService queryPerformanceService;

    @Autowired
    private AccessControlService accessControlService;

    /**
     * Record a query execution
     */
    @PostMapping("/record")
    public ResponseEntity<Map<String, Object>> recordQueryExecution(@RequestBody Map<String, Object> request) {
        try {
            String connectionId = (String) request.get("connectionId");
            accessControlService.assertCanManageConnectionContent(connectionId);
            String queryText = (String) request.get("queryText");
            Double executionTimeMs = ((Number) request.get("executionTimeMs")).doubleValue();
            Long rowsExamined = request.get("rowsExamined") != null ?
                    ((Number) request.get("rowsExamined")).longValue() : null;
            Long rowsSent = request.get("rowsSent") != null ?
                    ((Number) request.get("rowsSent")).longValue() : null;
            String databaseName = (String) request.get("databaseName");

            QueryPerformanceHistory history = queryPerformanceService.recordQueryExecution(
                    connectionId, queryText, executionTimeMs, rowsExamined, rowsSent, databaseName
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("id", history.getId());
            response.put("queryHash", history.getQueryHash());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error recording query execution", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Get all tracked queries for a connection
     */
    @GetMapping("/queries/{connectionId}")
    public ResponseEntity<Map<String, Object>> getTrackedQueries(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<Map<String, Object>> queries = queryPerformanceService.getTrackedQueries(connectionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("queries", queries);
            response.put("count", queries.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting tracked queries", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Get performance history for a specific query
     */
    @GetMapping("/history/{connectionId}/{queryHash}")
    public ResponseEntity<Map<String, Object>> getQueryHistory(
            @PathVariable String connectionId,
            @PathVariable String queryHash,
            @RequestParam(defaultValue = "7") int days
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<QueryPerformanceHistory> history = queryPerformanceService.getQueryHistory(
                    connectionId, queryHash, days
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("history", history);
            response.put("count", history.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting query history", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Get performance trend data for charting
     */
    @GetMapping("/trend/{connectionId}/{queryHash}")
    public ResponseEntity<Map<String, Object>> getPerformanceTrend(
            @PathVariable String connectionId,
            @PathVariable String queryHash,
            @RequestParam(defaultValue = "7") int days
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> trendData = queryPerformanceService.getPerformanceTrend(
                    connectionId, queryHash, days
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.putAll(trendData);

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting performance trend", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Get all performance regressions
     */
    @GetMapping("/regressions/{connectionId}")
    public ResponseEntity<Map<String, Object>> getRegressions(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "false") boolean unacknowledgedOnly
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<QueryPerformanceRegression> regressions = queryPerformanceService.getRegressions(
                    connectionId, unacknowledgedOnly
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("regressions", regressions);
            response.put("count", regressions.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting regressions", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Acknowledge a regression
     */
    @PostMapping("/regressions/{regressionId}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeRegression(
            @PathVariable Long regressionId,
            @RequestBody(required = false) Map<String, String> request
    ) {
        try {
            assertCanManageRegression(regressionId);
            queryPerformanceService.acknowledgeRegression(regressionId, actor());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Regression acknowledged");

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error acknowledging regression", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Resolve a regression
     */
    @PostMapping("/regressions/{regressionId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveRegression(
            @PathVariable Long regressionId,
            @RequestBody Map<String, String> request
    ) {
        try {
            assertCanManageRegression(regressionId);
            String resolutionNotes = request.get("resolutionNotes");
            queryPerformanceService.resolveRegression(regressionId, resolutionNotes);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Regression resolved");

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error resolving regression", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Trigger manual performance analysis
     */
    @PostMapping("/analyze/{connectionId}")
    public ResponseEntity<Map<String, Object>> triggerAnalysis(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            // This will be run async, so just trigger it
            new Thread(() -> {
                log.info("Manual performance analysis triggered for connection: {}", connectionId);
                // The scheduled task will handle the actual analysis
            }).start();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Performance analysis triggered");

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error triggering analysis", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Authorize a write keyed only on a regression id. The regression carries
     * its own connectionId, so resolve that and assert against it — a
     * regression id is not a capability, and these ids are sequential Longs,
     * so they are trivially enumerable. An unknown id reports 404 so the
     * endpoint cannot be used to probe which regressions exist.
     */
    private void assertCanManageRegression(Long regressionId) {
        String connectionId = queryPerformanceService.findConnectionIdForRegression(regressionId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Regression not found"));
        accessControlService.assertCanManageConnectionContent(connectionId);
    }

    /** The authenticated caller. Never trust a client-supplied actor name. */
    private String actor() {
        return accessControlService.requireCurrentUsername();
    }
}
