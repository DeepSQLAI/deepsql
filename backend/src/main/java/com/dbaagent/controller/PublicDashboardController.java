package com.dbaagent.controller;

import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.SavedDashboard;
import com.dbaagent.service.ConnectionChatAccessPolicyService;
import com.dbaagent.service.DashboardQueryShapeService;
import com.dbaagent.service.McpSqlGuardService;
import com.dbaagent.service.QueryExecutionContext;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.SavedDashboardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/**
 * Public (no-login) endpoints for dashboards the owner has published to the web.
 *
 * <p>The share token IS the authorization — there is no user here, so these are
 * permitAll (see SecurityConfig) and only resolve while {@code is_public} is true
 * (revoking flips it and every link 404s). Queries are read-only twice over
 * ({@link McpSqlGuardService} + a {@code READ_ONLY_ONLY} context) and scoped to
 * the dashboard's own connection. This is the accepted trade-off of a public BI
 * link: anyone with the token can view the dashboard and run read-only queries
 * against that one connection until the owner revokes it.
 */
@Slf4j
@RestController
@RequestMapping("/public/dashboards")
@RequiredArgsConstructor
public class PublicDashboardController {

    private static final int MAX_LIMIT = 5000;
    private static final int DEFAULT_LIMIT = 1000;

    private final SavedDashboardService savedDashboardService;
    private final ObjectMapper objectMapper;
    private final McpSqlGuardService sqlGuardService;
    private final DashboardQueryShapeService queryShapeService;
    private final ConnectionChatAccessPolicyService policyService;
    private final QueryExecutorService queryExecutorService;

    private Optional<SavedDashboard> publicDashboard(String token) {
        return savedDashboardService.findByShareToken(token)
            .filter(d -> Boolean.TRUE.equals(d.getIsPublic()));
    }

    /** The rendered dashboard (title + HTML only — never chat, connection id, or internals). */
    @GetMapping("/{token}")
    public ResponseEntity<?> get(@PathVariable String token,
                                 @RequestHeader(value = "X-Dashboard-Password", required = false) String password) {
        Optional<SavedDashboard> found = publicDashboard(token);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", "Not found"));
        }
        SavedDashboard d = found.get();
        if (!savedDashboardService.sharePasswordOk(d, password)) {
            // Map.of rejects null values, so build it conditionally.
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("success", false);
            body.put("requiresPassword", true);
            if (password != null && !password.isEmpty()) body.put("error", "Incorrect password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        String html = null;
        String title = d.getName();
        try {
            JsonNode cfg = objectMapper.readTree(d.getDashboardConfig());
            html = cfg.path("html").asText(null);
            if ((title == null || title.isBlank()) && cfg.hasNonNull("title")) title = cfg.get("title").asText();
        } catch (Exception ignore) { }
        if (html == null || html.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", "Not found"));
        }
        return ResponseEntity.ok(Map.of("success", true, "title", title == null ? "Dashboard" : title, "html", html));
    }

    public record PublicQueryRequest(String sql, Integer limit) { }

    /** Read-only query against the shared dashboard's connection, gated by the token. */
    @PostMapping("/{token}/query")
    public ResponseEntity<?> query(@PathVariable String token, @RequestBody PublicQueryRequest request,
                                   @RequestHeader(value = "X-Dashboard-Password", required = false) String password) {
        Optional<SavedDashboard> found = publicDashboard(token);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", "Not found"));
        }
        if (!savedDashboardService.sharePasswordOk(found.get(), password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "requiresPassword", true, "error", "Password required"));
        }
        if (request == null || request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "sql is required"));
        }
        McpSqlGuardService.ValidationOutcome guard = sqlGuardService.validateReadOnlySql(request.sql(), true);
        if (!guard.ok()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", guard.reason()));
        }
        // Read-only is not enough on an anonymous path: it asks whether the statement reads,
        // not whether this dashboard was published to run it. Without the shape check below, a
        // link shared to show one chart accepted "SELECT * FROM users" just as happily.
        // A policy added AFTER the link was shared must take effect on it. Enabling a share is
        // refused while a policy is active (SavedDashboardController), but nothing re-checked
        // afterwards, so a link created before the policy stayed live and unprotected —
        // "public-share" has no policy row, so resolveEffectivePolicy returns none() and
        // column protections and redaction never run. Re-checked here for the same reason
        // is_public is: revocation has to reach an already-issued link.
        if (policyService.hasActivePolicy(found.get().getConnectionId())) {
            log.info("Public dashboard query refused for token {}: connection has an active policy", token);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "success", false,
                "error", "This dashboard is no longer available publicly."));
        }
        Set<String> publishedShapes =
            queryShapeService.extractShapes(found.get().getDashboardConfig());
        if (!queryShapeService.matches(publishedShapes, request.sql())) {
            log.info("Public dashboard query refused for token {}: shape not published", token);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "This query is not part of the shared dashboard."));
        }
        int limit = request.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(request.limit(), MAX_LIMIT));
        try {
            QueryRequest qr = new QueryRequest();
            qr.setQuery(guard.normalizedQuery());
            qr.setLimit(limit);
            qr.setTimeoutSeconds(20);
            qr.setExecutionOrigin(QueryExecutionOrigin.API);
            // No user — the token is the authorization. Read-only context, connection
            // scoped to the dashboard's own connection.
            QueryResult result = queryExecutorService.executeQuery(
                found.get().getConnectionId(), qr, QueryExecutionContext.api("public-share"));
            return ResponseEntity.ok(Map.of(
                "success", true,
                "columns", result.getColumns() == null ? List.of() : result.getColumns(),
                "rows", result.getRows() == null ? List.of() : result.getRows()));
        } catch (Exception e) {
            log.info("Public dashboard query failed for token {}: {}", token, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "error", e.getMessage() == null ? "query failed" : e.getMessage()));
        }
    }
}
