package com.dbaagent.controller;

import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.service.McpSqlGuardService;
import com.dbaagent.service.QueryExecutionContext;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only query broker for generated dashboard artifacts.
 *
 * <p>A dashboard artifact is agent-generated HTML/JS that runs inside a sandboxed
 * iframe (opaque origin — no cookies, no DOM access to the app). It cannot reach
 * the database directly; instead its {@code deepsql.query(sql)} bridge posts the
 * SQL to the parent app, which calls THIS endpoint. Every query is therefore:
 * <ul>
 *   <li><b>Access-scoped</b> — {@link AccessControlService#assertCanReadConnectionContent}
 *       runs with the logged-in user's identity, so an artifact can only read what
 *       the user can.</li>
 *   <li><b>Read-only, twice over</b> — {@link McpSqlGuardService#validateReadOnlySql}
 *       rejects anything but SELECT/WITH/EXPLAIN before execution, and the query
 *       runs under a {@code READ_ONLY_ONLY} context regardless of the user's role.</li>
 * </ul>
 * This keeps the agent's generated code fully sandboxed while every data access
 * stays governed by the same guards as the rest of DeepSQL.
 */
@Slf4j
@RestController
@RequestMapping("/dashboards")
@RequiredArgsConstructor
public class DashboardQueryController {

    private static final int MAX_LIMIT = 5000;
    private static final int DEFAULT_LIMIT = 1000;

    private final AccessControlService accessControlService;
    private final McpSqlGuardService sqlGuardService;
    private final QueryExecutorService queryExecutorService;

    public record DashboardQueryRequest(String connectionId, String sql, Integer limit) { }

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody DashboardQueryRequest request) {
        if (request == null || request.connectionId() == null || request.connectionId().isBlank()
            || request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "connectionId and sql are required"));
        }

        // Identity-scoped: the artifact can only read what the logged-in user can.
        accessControlService.assertCanReadConnectionContent(request.connectionId());

        McpSqlGuardService.ValidationOutcome guard = sqlGuardService.validateReadOnlySql(request.sql(), true);
        if (!guard.ok()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", guard.reason()));
        }

        int limit = request.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(request.limit(), MAX_LIMIT));
        try {
            QueryRequest qr = new QueryRequest();
            qr.setQuery(guard.normalizedQuery());
            qr.setLimit(limit);
            // Bound each widget's query so a slow one fails fast (per-widget) instead
            // of holding a DB connection; the client aborts a few seconds later.
            qr.setTimeoutSeconds(20);
            qr.setExecutionOrigin(QueryExecutionOrigin.API);
            QueryResult result = queryExecutorService.executeQuery(
                request.connectionId(), qr,
                QueryExecutionContext.api(accessControlService.getCurrentUsername()));
            return ResponseEntity.ok(Map.of(
                "success", true,
                "columns", result.getColumns() == null ? java.util.List.of() : result.getColumns(),
                "rows", result.getRows() == null ? java.util.List.of() : result.getRows()));
        } catch (Exception e) {
            log.info("Dashboard artifact query failed for {}: {}", request.connectionId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "error", e.getMessage() == null ? "query failed" : e.getMessage()));
        }
    }
}
