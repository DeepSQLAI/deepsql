package com.dbaagent.controller;

import com.dbaagent.model.ExplainPlanAnalysis;
import com.dbaagent.model.McpReadOnlyExplainRequest;
import com.dbaagent.model.McpReadOnlyQueryRequest;
import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.service.ExplainPlanService;
import com.dbaagent.service.McpSqlGuardService;
import com.dbaagent.service.QueryActorContextHolder;
import com.dbaagent.service.QueryExecutionContext;
import com.dbaagent.service.QueryExecutionPolicyException;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * @deprecated MCP/CLI clients now hit the canonical /api/connections/{id}/query
 * and /api/explain/analyze endpoints, which run the same policy as the web SQL
 * Editor (role-gated admin mutations, WHERE-clause guard, two-step confirm) and
 * emit audit events via {@link com.dbaagent.service.SqlExecutionAuditService}.
 *
 * These read-only-locked endpoints are kept as a one-cycle alias for older MCP
 * clients (@deepsql/mcp &lt; 0.13.0) and will be removed in the 0.14.0 backend.
 * Every call logs a deprecation warning so we can confirm zero traffic before
 * deleting.
 */
@Deprecated(since = "0.13.0", forRemoval = true)
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@Slf4j
public class McpController {

    private final AccessControlService accessControlService;
    private final QueryExecutorService queryExecutorService;
    private final ExplainPlanService explainPlanService;
    private final McpSqlGuardService sqlGuardService;

    @PostMapping("/query-readonly")
    public ResponseEntity<?> executeReadOnlyQuery(@RequestBody McpReadOnlyQueryRequest request) {
        log.warn(
            "DEPRECATED endpoint /mcp/query-readonly called for connection {}. "
            + "Upgrade @deepsql/mcp to 0.13.0+; this endpoint will be removed in 0.14.0.",
            request == null ? null : request.getConnectionId()
        );
        if (request.getConnectionId() == null || request.getConnectionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Connection ID is required"));
        }

        McpSqlGuardService.ValidationOutcome validation =
            sqlGuardService.validateReadOnlySql(request.getQuery(), true);
        if (!validation.ok()) {
            return ResponseEntity.badRequest().body(Map.of("message", validation.reason()));
        }

        try {
            accessControlService.assertCanUseChatEditor(request.getConnectionId());

            QueryRequest queryRequest = new QueryRequest();
            queryRequest.setQuery(validation.normalizedQuery());
            queryRequest.setLimit(request.getLimit());
            queryRequest.setTimeoutSeconds(request.getTimeoutSeconds());
            queryRequest.setExecutionOrigin(QueryExecutionOrigin.MCP);

            QueryResult result = queryExecutorService.executeQuery(
                request.getConnectionId(),
                queryRequest,
                QueryExecutionContext.mcp(QueryActorContextHolder.currentUsername())
            );
            return ResponseEntity.ok(Map.of(
                "success", true,
                "result", result,
                "queryType", validation.firstKeyword()
            ));
        } catch (QueryExecutionPolicyException e) {
            return ResponseEntity.status(e.getHttpStatus())
                .body(Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "errorCode", e.getErrorCode()
                ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ResponseStatusException responseStatusException) {
                return ResponseEntity.status(responseStatusException.getStatusCode())
                    .body(Map.of("message", responseStatusException.getReason()));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "Query execution failed: " + e.getMessage()));
        }
    }

    @PostMapping("/explain-readonly")
    public ResponseEntity<?> explainReadOnlyQuery(@RequestBody McpReadOnlyExplainRequest request) {
        log.warn(
            "DEPRECATED endpoint /mcp/explain-readonly called for connection {}. "
            + "Upgrade @deepsql/mcp to 0.13.0+ and use /explain/analyze; "
            + "this endpoint will be removed in 0.14.0.",
            request == null ? null : request.getConnectionId()
        );
        if (request.getConnectionId() == null || request.getConnectionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Connection ID is required"));
        }

        McpSqlGuardService.ValidationOutcome validation =
            sqlGuardService.validateReadOnlySql(request.getQuery(), false);
        if (!validation.ok()) {
            return ResponseEntity.badRequest().body(Map.of("message", validation.reason()));
        }

        try {
            accessControlService.assertCanUseChatEditor(request.getConnectionId());
            ExplainPlanAnalysis analysis = explainPlanService.analyzeQuery(
                request.getConnectionId(),
                validation.normalizedQuery(),
                false
            );
            return ResponseEntity.ok(analysis);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ResponseStatusException responseStatusException) {
                return ResponseEntity.status(responseStatusException.getStatusCode())
                    .body(Map.of("message", responseStatusException.getReason()));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "Explain analysis failed: " + e.getMessage()));
        }
    }
}
