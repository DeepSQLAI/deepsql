package com.dbaagent.controller;

import com.dbaagent.model.PerformanceAction;
import com.dbaagent.model.PerformanceAction.ActionCategory;
import com.dbaagent.model.PerformanceAction.ActionSource;
import com.dbaagent.model.PerformanceAction.ActionStatus;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.dto.SlowQueryHistorySummary;
import com.dbaagent.service.PerformanceActionAggregatorService;
import com.dbaagent.service.PerformanceActionAggregatorService.ActionSummary;
import com.dbaagent.service.PerformanceActionAggregatorService.RefreshResult;
import com.dbaagent.service.SlowQueryHistoryService;
import com.dbaagent.service.security.AccessControlService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * REST controller for unified performance actions.
 * Provides endpoints for listing, filtering, refreshing, and managing performance recommendations.
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/performance-actions")
@RequiredArgsConstructor
@Slf4j
public class PerformanceActionController {

    private static final int MAX_QUERY_PREVIEW_LENGTH = 300;
    private static final int MAX_AFFECTED_QUERIES = 100;

    private final PerformanceActionAggregatorService aggregatorService;
    private final SlowQueryHistoryService slowQueryHistoryService;
    private final AccessControlService accessControlService;

    /**
     * Get all pending performance actions for a connection, sorted by ROI.
     */
    @GetMapping("/{connectionId}")
    public ResponseEntity<List<PerformanceAction>> getActions(
            @PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        log.debug("Getting performance actions for connection: {}", connectionId);
        List<PerformanceAction> actions = aggregatorService.getAggregatedActions(connectionId);
        return ResponseEntity.ok(actions);
    }

    /**
     * Get top N actions by ROI.
     */
    @GetMapping("/{connectionId}/top")
    public ResponseEntity<List<PerformanceAction>> getTopActions(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "10") int limit) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        log.debug("Getting top {} actions for connection: {}", limit, connectionId);
        List<PerformanceAction> actions = aggregatorService.getTopActions(connectionId, limit);
        return ResponseEntity.ok(actions);
    }

    /**
     * Get actions filtered by category.
     */
    @GetMapping("/{connectionId}/category/{category}")
    public ResponseEntity<List<PerformanceAction>> getActionsByCategory(
            @PathVariable String connectionId,
            @PathVariable ActionCategory category) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        log.debug("Getting actions for connection {} by category: {}", connectionId, category);
        List<PerformanceAction> actions = aggregatorService.getActionsByCategory(connectionId, category);
        return ResponseEntity.ok(actions);
    }

    /**
     * Get actions filtered by source.
     */
    @GetMapping("/{connectionId}/source/{source}")
    public ResponseEntity<List<PerformanceAction>> getActionsBySource(
            @PathVariable String connectionId,
            @PathVariable ActionSource source) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        log.debug("Getting actions for connection {} by source: {}", connectionId, source);
        List<PerformanceAction> actions = aggregatorService.getActionsBySource(connectionId, source);
        return ResponseEntity.ok(actions);
    }

    /**
     * Get summary statistics for a connection.
     */
    @GetMapping("/{connectionId}/summary")
    public ResponseEntity<ActionSummary> getSummary(
            @PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        log.debug("Getting action summary for connection: {}", connectionId);
        ActionSummary summary = aggregatorService.getSummary(connectionId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Refresh actions by re-collecting from all sources.
     */
    @PostMapping("/{connectionId}/refresh")
    public ResponseEntity<RefreshResult> refreshActions(
            @PathVariable String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        log.info("Refreshing performance actions for connection: {}", connectionId);
        RefreshResult result = aggregatorService.refreshActions(connectionId);
        return ResponseEntity.ok(result);
    }

    /**
     * Update action status (complete, dismiss, etc.).
     */
    @PutMapping("/{actionId}/status")
    public ResponseEntity<PerformanceAction> updateStatus(
            @PathVariable String actionId,
            @RequestBody StatusUpdateRequest request) {
        log.info("Updating action {} status to: {}", actionId, request.getStatus());
        assertCanManageAction(actionId);
        // The resolver is the authenticated caller, never request.getResolvedBy():
        // that was client-supplied, so the audit trail could name anyone.
        PerformanceAction updated = aggregatorService.updateStatus(
                actionId,
                request.getStatus(),
                accessControlService.requireCurrentUsername(),
                request.getNotes());
        return ResponseEntity.ok(updated);
    }

    /**
     * Batch update action statuses.
     */
    @PutMapping("/batch-status")
    public ResponseEntity<List<PerformanceAction>> batchUpdateStatus(
            @RequestBody BatchStatusUpdateRequest request) {
        log.info("Batch updating {} actions to status: {}",
                request.getActionIds().size(), request.getStatus());
        request.getActionIds().forEach(this::assertCanManageAction);

        List<PerformanceAction> updated = request.getActionIds().stream()
                .map(id -> aggregatorService.updateStatus(
                        id,
                        request.getStatus(),
                        accessControlService.requireCurrentUsername(),
                        request.getNotes()))
                .toList();

        return ResponseEntity.ok(updated);
    }

    /**
     * Get affected queries for a performance action (from latest slow query analysis).
     * Filters slow queries that touch the action's target table.
     */
    @GetMapping("/action/{actionId}/affected-queries")
    public ResponseEntity<AffectedQueriesResponse> getAffectedQueries(@PathVariable String actionId) {
        Optional<PerformanceAction> actionOpt = aggregatorService.getActionById(actionId);
        if (actionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PerformanceAction action = actionOpt.get();
        String connectionId = action.getConnectionId();
        accessControlService.assertCanReadConnectionContent(connectionId);
        String tableName = action.getTargetObject();
        if (tableName == null || tableName.isBlank()) {
            return ResponseEntity.ok(new AffectedQueriesResponse(List.of(), 0));
        }
        String tableLower = tableName.toLowerCase();

        Optional<SlowQueryHistory> latestOpt = slowQueryHistoryService.getLatestHistory(connectionId);
        if (latestOpt.isEmpty()) {
            return ResponseEntity.ok(new AffectedQueriesResponse(List.of(), 0));
        }

        SlowQueryAnalysis analysis = slowQueryHistoryService.getAnalysisData(latestOpt.get());
        if (analysis == null || analysis.getTopSlowQueries() == null) {
            return ResponseEntity.ok(new AffectedQueriesResponse(List.of(), 0));
        }

        List<AffectedQueryItem> items = new ArrayList<>();
        for (SlowQuery q : analysis.getTopSlowQueries()) {
            if (q.getAffectedTables() == null) continue;
            boolean matches = q.getAffectedTables().stream()
                .anyMatch(t -> t != null && t.toLowerCase().equals(tableLower));
            if (!matches) continue;
            String text = q.getQueryText();
            if (text != null && text.length() > MAX_QUERY_PREVIEW_LENGTH) {
                text = text.substring(0, MAX_QUERY_PREVIEW_LENGTH) + "...";
            }
            items.add(new AffectedQueryItem(
                text != null ? text : "",
                q.getAvgExecutionTimeMs() != null ? q.getAvgExecutionTimeMs() : 0.0,
                q.getCallCount() != null ? q.getCallCount() : 0L
            ));
            if (items.size() >= MAX_AFFECTED_QUERIES) break;
        }

        // If no queries found in latest snapshot, try searching recent history
        if (items.isEmpty()) {
            List<SlowQueryHistorySummary> recentSummaries = slowQueryHistoryService.getRecentHistorySummaries(connectionId);
            // Skip the first one as we already checked it (if it exists)
            int startIndex = latestOpt.isPresent() ? 1 : 0;
            int historyLimit = 5; // Check up to 5 recent snapshots
            
            Set<String> seenQueries = new HashSet<>();

            for (int i = startIndex; i < Math.min(recentSummaries.size(), startIndex + historyLimit); i++) {
                if (items.size() >= MAX_AFFECTED_QUERIES) break;
                
                String historyId = recentSummaries.get(i).getId();
                Optional<SlowQueryHistory> historyOpt = slowQueryHistoryService.getHistoryById(historyId);
                
                if (historyOpt.isPresent()) {
                    SlowQueryAnalysis histAnalysis = slowQueryHistoryService.getAnalysisData(historyOpt.get());
                    if (histAnalysis != null && histAnalysis.getTopSlowQueries() != null) {
                        for (SlowQuery q : histAnalysis.getTopSlowQueries()) {
                            if (q.getAffectedTables() == null) continue;
                            boolean matches = q.getAffectedTables().stream()
                                .anyMatch(t -> t != null && t.toLowerCase().equals(tableLower));
                            
                            if (!matches) continue;
                            
                            String text = q.getQueryText();
                            if (text == null || seenQueries.contains(text)) continue;
                            
                            seenQueries.add(text);
                            
                            if (text.length() > MAX_QUERY_PREVIEW_LENGTH) {
                                text = text.substring(0, MAX_QUERY_PREVIEW_LENGTH) + "...";
                            }
                            
                            items.add(new AffectedQueryItem(
                                text,
                                q.getAvgExecutionTimeMs() != null ? q.getAvgExecutionTimeMs() : 0.0,
                                q.getCallCount() != null ? q.getCallCount() : 0L
                            ));
                            
                            if (items.size() >= MAX_AFFECTED_QUERIES) break;
                        }
                    }
                }
            }
        }

        return ResponseEntity.ok(new AffectedQueriesResponse(items, items.size()));
    }

    // ==================== Request / Response DTOs ====================

    @Data
    public static class StatusUpdateRequest {
        private ActionStatus status;
        private String resolvedBy;
        private String notes;
    }

    @Data
    public static class BatchStatusUpdateRequest {
        private List<String> actionIds;
        private ActionStatus status;
        private String resolvedBy;
        private String notes;
    }

    @Data
    @AllArgsConstructor
    public static class AffectedQueriesResponse {
        private final List<AffectedQueryItem> queries;
        private final int totalInAnalysis;
    }

    @Data
    @AllArgsConstructor
    public static class AffectedQueryItem {
        private final String queryText;
        private final Double avgExecutionTimeMs;
        private final Long callCount;
    }

    /**
     * Authorize a write keyed only on an action id. The action carries its own
     * connectionId, so resolve that first and assert against it — an action id
     * is not a capability. An unknown id reports 404 rather than 403 so the
     * endpoint cannot be used to probe which action ids exist.
     */
    private void assertCanManageAction(String actionId) {
        PerformanceAction action = aggregatorService.getActionById(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found"));
        accessControlService.assertCanManageConnectionContent(action.getConnectionId());
    }
}
