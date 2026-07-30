package com.dbaagent.controller;

import com.dbaagent.model.QueryPlanCache;
import com.dbaagent.model.QueryPlanComparison;
import com.dbaagent.service.QueryPlanCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for query plan caching and comparison
 */
@RestController
@RequestMapping("/query-plans")
@RequiredArgsConstructor
@Slf4j
public class QueryPlanController {

    private final QueryPlanCacheService planCacheService;

    /**
     * Capture execution plan for a query
     */
    @PostMapping("/{connectionId}/capture")
    public ResponseEntity<QueryPlanCache> capturePlan(
            @PathVariable String connectionId,
            @RequestBody Map<String, Object> request) {

        String query = (String) request.get("query");
        boolean analyze = Boolean.TRUE.equals(request.get("analyze"));

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        QueryPlanCache plan = planCacheService.capturePlan(connectionId, query, analyze);
        return ResponseEntity.ok(plan);
    }

    /**
     * Get recent plans for a connection
     */
    @GetMapping("/{connectionId}/recent")
    public ResponseEntity<List<QueryPlanCache>> getRecentPlans(@PathVariable String connectionId) {
        return ResponseEntity.ok(planCacheService.getRecentPlans(connectionId));
    }

    /**
     * Get plans for a specific query (by hash)
     */
    @GetMapping("/{connectionId}/query/{queryHash}")
    public ResponseEntity<List<QueryPlanCache>> getPlansForQuery(
            @PathVariable String connectionId,
            @PathVariable String queryHash) {
        return ResponseEntity.ok(planCacheService.getPlansForQuery(connectionId, queryHash));
    }

    /**
     * Set a plan as baseline
     */
    @PostMapping("/plans/{planId}/set-baseline")
    public ResponseEntity<Map<String, String>> setBaseline(@PathVariable String planId) {
        planCacheService.setBaseline(planId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Plan set as baseline"
        ));
    }

    /**
     * Get plan regressions
     */
    @GetMapping("/{connectionId}/regressions")
    public ResponseEntity<List<QueryPlanComparison>> getRegressions(@PathVariable String connectionId) {
        return ResponseEntity.ok(planCacheService.getRegressions(connectionId));
    }

    /**
     * Get unacknowledged regressions
     */
    @GetMapping("/{connectionId}/regressions/unacknowledged")
    public ResponseEntity<List<QueryPlanComparison>> getUnacknowledgedRegressions(@PathVariable String connectionId) {
        return ResponseEntity.ok(planCacheService.getUnacknowledgedRegressions(connectionId));
    }

    /**
     * Acknowledge regressions
     */
    @PostMapping("/{connectionId}/regressions/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeRegressions(
            @PathVariable String connectionId,
            @RequestBody List<String> comparisonIds,
            @RequestParam(required = false, defaultValue = "user") String acknowledgedBy) {

        int count = planCacheService.acknowledgeRegressions(comparisonIds, acknowledgedBy);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "acknowledgedCount", count
        ));
    }

    /**
     * Get plan statistics
     */
    @GetMapping("/{connectionId}/stats")
    public ResponseEntity<Map<String, Object>> getPlanStats(@PathVariable String connectionId) {
        return ResponseEntity.ok(planCacheService.getPlanStats(connectionId));
    }
}
