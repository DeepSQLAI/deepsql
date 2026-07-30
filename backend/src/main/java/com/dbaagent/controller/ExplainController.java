package com.dbaagent.controller;

import com.dbaagent.model.AnalysisHistory;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ExplainPlanAnalysis;
import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.service.AnalysisHistoryService;
import com.dbaagent.service.ClientContext;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.ExplainPlanService;
import com.dbaagent.service.QueryExecutionContext;
import com.dbaagent.service.QueryExecutionPolicyException;
import com.dbaagent.service.QueryExecutionPolicyService;
import com.dbaagent.service.SqlExecutionAuditService;
import com.dbaagent.service.security.AccessControlService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for SQL execution plan analysis.
 *
 * Two safety properties matter on this controller:
 *
 *   1. `useAnalyze=true` actually runs the underlying statement inside
 *      EXPLAIN ANALYZE — so for any mutating statement, that's a real
 *      database write. We route those through QueryExecutionPolicyService
 *      with `QueryExecutionContext.editor(...)` so the same role/WHERE/
 *      confirmation gates that protect /api/connections/{id}/query
 *      protect this path too.
 *
 *   2. Every call — success, blocked, or failed — emits a SecurityEvent so
 *      audit dashboards can see CLI/MCP/Editor traffic with one filter.
 */
@RestController
@RequestMapping("/explain")
@RequiredArgsConstructor
@Slf4j
public class ExplainController {

    private final ExplainPlanService explainPlanService;
    private final AnalysisHistoryService historyService;
    private final AccessControlService accessControlService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;
    private final QueryExecutionPolicyService queryExecutionPolicyService;
    private final SqlExecutionAuditService sqlExecutionAuditService;

    /**
     * Analyze a query's execution plan. With `useAnalyze=true` the query is
     * actually executed (Postgres/MySQL EXPLAIN ANALYZE semantics), so the
     * mutation policy applies to the underlying statement.
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeQuery(
        @RequestBody ExplainRequest request,
        HttpServletRequest httpRequest
    ) {
        ClientContext client = ClientContext.fromRequest(httpRequest);
        String connectionId = request.getConnectionId();
        QueryRequest auditQueryRequest = buildAuditQueryRequest(request);
        ConnectionRequest connectionRequest = null;

        try {
            accessControlService.assertCanUseChatEditor(connectionId);
            log.info("EXPLAIN analysis requested for connection: {} (useAnalyze={})", connectionId, request.isUseAnalyze());

            // ANALYZE actually executes the SQL. Route the underlying
            // statement through the same policy gate the SQL Editor uses so
            // a developer can't bypass the mutation guard by sending
            // useAnalyze=true with `DELETE FROM users`.
            if (request.isUseAnalyze()) {
                connectionRequest = credentialService.getDecryptedConnection(connectionId);
                String dbType = providerRegistry.getCanonicalName(connectionRequest.getDbType());
                queryExecutionPolicyService.enforce(
                    auditQueryRequest,
                    QueryExecutionContext.editor(
                        accessControlService.getCurrentUsername(),
                        accessControlService.isCurrentUserAdmin(),
                        Boolean.TRUE.equals(request.getMutationConfirmed())
                    ),
                    dbType
                );
            }

            ExplainPlanAnalysis analysis = explainPlanService.analyzeQuery(
                connectionId,
                request.getQuery(),
                request.isUseAnalyze()
            );

            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.executed()
                .operation("analyze-plan")
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(auditQueryRequest)
                .explainResult(analysis)
                .useAnalyze(request.isUseAnalyze())
                .httpRequest(httpRequest)
                .client(client));

            return ResponseEntity.ok(analysis);

        } catch (QueryExecutionPolicyException e) {
            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.blocked(e.getMessage())
                .operation("analyze-plan")
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(auditQueryRequest)
                .useAnalyze(request.isUseAnalyze())
                .httpRequest(httpRequest)
                .client(client));
            return ResponseEntity.status(e.getHttpStatus()).body(Map.of(
                "message", e.getMessage(),
                "errorCode", e.getErrorCode(),
                "queryType", e.getQueryType(),
                "requiresConfirmation", e.isRequiresConfirmation(),
                "warnings", e.getWarnings()
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            String reason = e instanceof ResponseStatusException rse ? rse.getReason() : e.getMessage();
            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.failed(reason)
                .operation("analyze-plan")
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(auditQueryRequest)
                .useAnalyze(request.isUseAnalyze())
                .httpRequest(httpRequest)
                .client(client));

            if (e instanceof ResponseStatusException responseStatusException) {
                return ResponseEntity.status(responseStatusException.getStatusCode())
                    .body(Map.of("message", responseStatusException.getReason()));
            }
            log.error("Error analyzing query execution plan", e);
            HttpStatus status = isSqlSyntaxError(e) || e instanceof IllegalArgumentException
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.INTERNAL_SERVER_ERROR;
            String message = e.getMessage() != null ? e.getMessage() : "Failed to analyze query";
            return ResponseEntity.status(status).body(Map.of("message", message));
        }
    }

    /**
     * Build a QueryRequest used only for audit + policy classification.
     * Carries the user's mutationConfirmed flag through so admin-confirmed
     * ANALYZE runs aren't stuck on the confirmation gate.
     */
    private QueryRequest buildAuditQueryRequest(ExplainRequest request) {
        QueryRequest qr = new QueryRequest();
        qr.setQuery(request.getQuery());
        qr.setExecutionOrigin(QueryExecutionOrigin.EDITOR);
        qr.setMutationConfirmed(Boolean.TRUE.equals(request.getMutationConfirmed()));
        return qr;
    }

    /**
     * Save analysis to history
     */
    @PostMapping("/history")
    public ResponseEntity<HistoryResponse> saveHistory(
        @RequestBody SaveHistoryRequest request
    ) {
        try {
            accessControlService.assertCanUseChatEditor(request.getConnectionId());
            log.info("Saving analysis history for connection: {}", request.getConnectionId());

            AnalysisHistory history = historyService.saveAnalysis(
                request.getConnectionId(),
                request.getQuery(),
                request.getUseAnalyze(),
                request.getAnalysisData(),
                request.getPerformanceScore(),
                request.getIssueCount(),
                request.getUserId()
            );

            HistoryResponse response = convertToResponse(history);
            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ResponseStatusException responseStatusException) {
                return ResponseEntity.status(responseStatusException.getStatusCode()).build();
            }
            log.error("Error saving analysis history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private boolean isSqlSyntaxError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BadSqlGrammarException || current instanceof SQLSyntaxErrorException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Get analysis history for a connection
     */
    @GetMapping("/history/{connectionId}")
    public ResponseEntity<List<HistoryResponse>> getHistory(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanUseChatEditor(connectionId);
            log.info("Fetching analysis history for connection: {}", connectionId);

            List<AnalysisHistory> histories = historyService.getRecentHistory(connectionId);
            List<HistoryResponse> responses = histories.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

            return ResponseEntity.ok(responses);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ResponseStatusException responseStatusException) {
                return ResponseEntity.status(responseStatusException.getStatusCode()).build();
            }
            log.error("Error fetching analysis history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete a specific history entry
     */
    @DeleteMapping("/history/{id}")
    public ResponseEntity<Map<String, String>> deleteHistory(
        @PathVariable String id
    ) {
        try {
            accessControlService.assertCanAccessAnalysisHistory(id);
            log.info("Deleting analysis history: {}", id);
            historyService.deleteHistory(id);

            return ResponseEntity.ok(Map.of("message", "History deleted successfully"));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ResponseStatusException responseStatusException) {
                return ResponseEntity.status(responseStatusException.getStatusCode())
                    .body(Map.of("message", responseStatusException.getReason()));
            }
            log.error("Error deleting analysis history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete all history for a connection
     */
    @DeleteMapping("/history/connection/{connectionId}")
    public ResponseEntity<Map<String, String>> deleteAllHistory(
        @PathVariable String connectionId
    ) {
        try {
            accessControlService.assertCanUseChatEditor(connectionId);
            log.info("Deleting all history for connection: {}", connectionId);
            historyService.deleteAllForConnection(connectionId);

            return ResponseEntity.ok(Map.of("message", "All history deleted successfully"));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ResponseStatusException responseStatusException) {
                return ResponseEntity.status(responseStatusException.getStatusCode())
                    .body(Map.of("message", responseStatusException.getReason()));
            }
            log.error("Error deleting all history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Convert AnalysisHistory entity to response DTO
     */
    private HistoryResponse convertToResponse(AnalysisHistory history) {
        Map<String, Object> analysisData = historyService.getAnalysisDataAsMap(history);

        HistoryResponse response = new HistoryResponse();
        response.setId(history.getId());
        response.setConnectionId(history.getConnectionId());
        response.setUserId(history.getUserId());
        response.setQuery(history.getQuery());
        response.setUseAnalyze(history.getUseAnalyze());
        response.setAnalysisData(analysisData);
        response.setPerformanceScore(history.getPerformanceScore());
        response.setIssueCount(history.getIssueCount());
        response.setTimestamp(history.getCreatedAt().toString());

        return response;
    }

    /**
     * Request model for EXPLAIN analysis
     */
    @Data
    public static class ExplainRequest {
        private String connectionId;
        private String query;
        private boolean useAnalyze = false;  // If true, use EXPLAIN ANALYZE (actually executes query)
        // When useAnalyze=true with a mutating statement, the policy gate
        // returns requiresConfirmation on the first call; client resends
        // with mutationConfirmed=true.
        private Boolean mutationConfirmed = Boolean.FALSE;
    }

    /**
     * Request model for saving analysis history
     */
    @Data
    public static class SaveHistoryRequest {
        private String connectionId;
        private String userId;
        private String query;
        private Boolean useAnalyze;
        private Map<String, Object> analysisData;
        private Integer performanceScore;
        private Integer issueCount;
    }

    /**
     * Response model for analysis history
     */
    @Data
    public static class HistoryResponse {
        private String id;
        private String connectionId;
        private String userId;
        private String query;
        private Boolean useAnalyze;
        private Map<String, Object> analysisData;
        private Integer performanceScore;
        private Integer issueCount;
        private String timestamp;
    }
}
