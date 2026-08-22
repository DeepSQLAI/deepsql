package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ExplainPlanAnalysis;
import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.User;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.util.SecurityHashUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Centralized audit logging for SQL execution and plan-analysis surfaces.
 *
 * Originally inlined into SchemaController.auditEditorQuery; lifted here so
 * every controller that runs user SQL (web Editor, CLI/MCP via the canonical
 * endpoints, plan analysis) writes the same shape of event with the same
 * metadata fields. The audit row carries three orthogonal dimensions:
 *
 *   - WHO   user_id, email (resolved from the bearer token)
 *   - WHAT  connection, query hash + truncated text, mode, outcome
 *   - WHERE clientType (cli/mcp/editor), clientAgent (claude-code/cursor/…),
 *           clientVersion, client IP, user agent
 *
 * All failures during audit logging are swallowed and logged — the audit
 * subsystem must never block or fail an otherwise successful SQL execution.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SqlExecutionAuditService {

    private static final int QUERY_TEXT_AUDIT_LIMIT = 4000;
    private static final int REASON_AUDIT_LIMIT = 500;

    private final SecurityEventService securityEventService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;

    /**
     * One-call audit for a SQL execution. Use the static `success/blocked/failed`
     * factories on AuditRecord to construct the right SecurityEventType+Outcome
     * pair without repeating the mapping at call sites.
     */
    public void record(AuditRecord rec) {
        try {
            String username = accessControlService.getCurrentUsername();
            Optional<User> actor = username == null ? Optional.empty() : userRepository.findByUsername(username);

            Map<String, Object> metadata = new LinkedHashMap<>();

            // Where the request came from. Listed first so audit dashboards
            // can filter quickly by client type without scrolling through
            // query text.
            ClientContext client = rec.client == null ? ClientContext.unknown() : rec.client;
            metadata.put("clientType", client.clientType());
            metadata.put("clientAgent", client.clientAgent());
            metadata.put("clientVersion", client.clientVersion());

            // Origin retained for backward compatibility with existing
            // dashboards keyed off QueryExecutionOrigin (CHAT vs EDITOR vs
            // INTERNAL). New code should prefer clientType for the granular
            // breakdown.
            metadata.put("origin", QueryExecutionOrigin.normalized(
                rec.queryRequest == null ? null : rec.queryRequest.getExecutionOrigin()
            ).name());

            metadata.put("operation", rec.operation == null ? "execute" : rec.operation);
            metadata.put("connectionId", rec.connectionId);
            metadata.put("connectionName", rec.connectionRequest == null ? null : rec.connectionRequest.getConnectionName());
            metadata.put("dbType", rec.connectionRequest == null ? null : rec.connectionRequest.getDbType());
            if (rec.executionId != null) {
                metadata.put("executionId", rec.executionId);
            }

            String queryText = rec.queryRequest == null ? null : rec.queryRequest.getQuery();
            metadata.put("queryHash", SecurityHashUtil.sha256Hex(queryText == null ? "" : queryText));
            metadata.put("queryText", truncate(queryText, QUERY_TEXT_AUDIT_LIMIT));

            if (rec.queryRequest != null) {
                metadata.put("limit", rec.queryRequest.getLimit());
                metadata.put("timeoutSeconds", rec.queryRequest.getTimeoutSeconds());
                metadata.put("mutationConfirmed", Boolean.TRUE.equals(rec.queryRequest.getMutationConfirmed()));
            }

            if (rec.useAnalyze != null) {
                metadata.put("useAnalyze", rec.useAnalyze);
            }

            if (rec.queryResult != null) {
                metadata.put("rowCount", rec.queryResult.getRowCount());
                metadata.put("executionTimeMs", rec.queryResult.getExecutionTimeMs());
                metadata.put("returnedColumns", rec.queryResult.getColumns());
            }
            if (rec.explainResult != null) {
                // Plan analysis doesn't return rows; capture high-level
                // signals so dashboards can spot patterns like "every plan
                // is heavy but no one's acting on suggestions."
                metadata.put("planIssueCount", rec.explainResult.getIssues() == null ? 0 : rec.explainResult.getIssues().size());
                metadata.put("planNodeCount", rec.explainResult.getNodeCount());
                metadata.put("planTotalTimeMs", rec.explainResult.getTotalTimeMs());
                metadata.put("explainExecutedTheQuery", Boolean.TRUE.equals(rec.explainResult.getWasExecuted()));
            }

            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(rec.eventType)
                .outcome(rec.outcome)
                .userId(actor.map(User::getId).orElse(null))
                .email(actor.map(User::getEmail).orElse(null))
                .targetResource("connection:" + rec.connectionId)
                .reason(truncate(rec.failureReason, REASON_AUDIT_LIMIT))
                .clientIp(clientIp(rec.httpRequest))
                .userAgent(userAgent(rec.httpRequest))
                .requestId(requestId(rec.httpRequest))
                .metadata(metadata)
                .build());
        } catch (Exception auditError) {
            log.warn(
                "Failed to record SQL execution audit event for connection {} ({}): {}",
                rec.connectionId,
                rec.eventType,
                auditError.getMessage()
            );
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "…";
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }

    private static String requestId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String requestId = request.getHeader("X-Request-ID");
        return requestId == null || requestId.isBlank() ? null : requestId;
    }

    /**
     * Builder-shaped record describing one audit event. Use the static
     * factories below to construct one with the right enum pair set.
     */
    public static final class AuditRecord {
        private SecurityEventType eventType;
        private SecurityEventOutcome outcome;
        private String operation;          // "execute" | "analyze-plan"
        private String connectionId;
        private ConnectionRequest connectionRequest;
        private QueryRequest queryRequest;
        private QueryResult queryResult;
        private ExplainPlanAnalysis explainResult;
        private Boolean useAnalyze;
        private String failureReason;
        private HttpServletRequest httpRequest;
        private ClientContext client;
        private String executionId;

        private AuditRecord() {}

        // ── factories ─────────────────────────────────────────────────────

        public static AuditRecord executed() {
            AuditRecord r = new AuditRecord();
            r.eventType = SecurityEventType.EDITOR_QUERY_EXECUTED;
            r.outcome = SecurityEventOutcome.SUCCESS;
            r.operation = "execute";
            return r;
        }

        public static AuditRecord blocked(String reason) {
            AuditRecord r = new AuditRecord();
            r.eventType = SecurityEventType.EDITOR_QUERY_BLOCKED;
            r.outcome = SecurityEventOutcome.FAILURE;
            r.operation = "execute";
            r.failureReason = reason;
            return r;
        }

        public static AuditRecord failed(String reason) {
            AuditRecord r = new AuditRecord();
            r.eventType = SecurityEventType.EDITOR_QUERY_FAILED;
            r.outcome = SecurityEventOutcome.FAILURE;
            r.operation = "execute";
            r.failureReason = reason;
            return r;
        }

        /**
         * A deliberate cancel request against a still-running query, distinct
         * from {@link #cancelNoOp()}. Without a dedicated event type, the only
         * trace of a cancel was the killed query's own thread logging
         * {@code pg_terminate_backend}'s error as an ordinary EDITOR_QUERY_FAILED
         * — indistinguishable from any other failure, and absent entirely when
         * the target had already finished before the kill reached it.
         */
        public static AuditRecord cancelled(String sessionPid) {
            AuditRecord r = new AuditRecord();
            r.eventType = SecurityEventType.EDITOR_QUERY_CANCELLED;
            r.outcome = SecurityEventOutcome.SUCCESS;
            r.operation = "cancel";
            r.failureReason = sessionPid == null ? null : "terminated session pid " + sessionPid;
            return r;
        }

        /** Cancel requested for an execution id that was already finished or unknown. */
        public static AuditRecord cancelNoOp() {
            AuditRecord r = new AuditRecord();
            r.eventType = SecurityEventType.EDITOR_QUERY_CANCELLED;
            r.outcome = SecurityEventOutcome.INFO;
            r.operation = "cancel";
            r.failureReason = "query was no longer running";
            return r;
        }

        // ── fluent setters ────────────────────────────────────────────────

        public AuditRecord operation(String op) { this.operation = op; return this; }
        public AuditRecord connectionId(String id) { this.connectionId = id; return this; }
        public AuditRecord connectionRequest(ConnectionRequest c) { this.connectionRequest = c; return this; }
        public AuditRecord queryRequest(QueryRequest q) { this.queryRequest = q; return this; }
        public AuditRecord queryResult(QueryResult r) { this.queryResult = r; return this; }
        public AuditRecord explainResult(ExplainPlanAnalysis r) { this.explainResult = r; return this; }
        public AuditRecord useAnalyze(Boolean v) { this.useAnalyze = v; return this; }
        public AuditRecord httpRequest(HttpServletRequest r) { this.httpRequest = r; return this; }
        public AuditRecord client(ClientContext c) { this.client = c; return this; }
        public AuditRecord executionId(String id) { this.executionId = id; return this; }
        public AuditRecord eventType(SecurityEventType t) { this.eventType = t; return this; }
        public AuditRecord outcome(SecurityEventOutcome o) { this.outcome = o; return this; }
    }
}
