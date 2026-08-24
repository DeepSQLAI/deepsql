package com.dbaagent.controller;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ExplainPlanAnalysis;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.service.AnalysisHistoryService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.ExplainPlanService;
import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.service.QueryExecutionContext;
import com.dbaagent.service.QueryExecutionPolicyException;
import com.dbaagent.service.QueryExecutionPolicyService;
import com.dbaagent.service.SqlExecutionAuditService;
import com.dbaagent.service.security.AccessControlService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the new safety property in ExplainController:
 *
 *   - useAnalyze=true routes the underlying query through
 *     QueryExecutionPolicyService.enforce(...) BEFORE running EXPLAIN ANALYZE.
 *     Developers can't bypass the mutation guard by piggybacking on the
 *     analyzer endpoint.
 *   - useAnalyze=false is purely plan extraction (no execution), so the
 *     policy gate is skipped and any connection-reader can call it.
 *   - Every call audits, success or blocked.
 */
@ExtendWith(MockitoExtension.class)
class ExplainControllerPolicyTest {

    @Mock private ExplainPlanService explainPlanService;
    @Mock private AnalysisHistoryService historyService;
    @Mock private AccessControlService accessControlService;
    @Mock private CredentialService credentialService;
    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private QueryExecutionPolicyService queryExecutionPolicyService;
    @Mock private SqlExecutionAuditService sqlExecutionAuditService;
    @Mock private HttpServletRequest httpRequest;

    private ExplainController controller;

    @BeforeEach
    void setUp() {
        controller = new ExplainController(
            explainPlanService,
            historyService,
            accessControlService,
            credentialService,
            providerRegistry,
            queryExecutionPolicyService,
            sqlExecutionAuditService
        );
    }

    @Test
    void useAnalyzeTrue_runsPolicyGateBeforeAnalyzing() {
        givenConnection("conn-1", "postgres");
        when(explainPlanService.analyzeQuery(eq("conn-1"), anyString(), eq(true)))
            .thenReturn(new ExplainPlanAnalysis());

        ResponseEntity<?> response = controller.analyzeQuery(
            request("conn-1", "DELETE FROM users WHERE id = 1", true),
            httpRequest
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // The policy gate is the only thing standing between an
        // unprivileged user and a real DELETE — verify it ran.
        verify(queryExecutionPolicyService).enforce(any(), any(), eq("postgres"));
        verify(explainPlanService).analyzeQuery(eq("conn-1"), eq("DELETE FROM users WHERE id = 1"), eq(true));
        verify(sqlExecutionAuditService, times(1)).record(any());
    }

    @Test
    void useAnalyzeTrue_policyBlocksDeveloperMutation_neverHitsAnalyzer() {
        givenConnection("conn-1", "postgres");
        when(queryExecutionPolicyService.enforce(any(), any(), eq("postgres")))
            .thenThrow(QueryExecutionPolicyException.editorMutationForbidden("DELETE"));

        ResponseEntity<?> response = controller.analyzeQuery(
            request("conn-1", "DELETE FROM users WHERE id = 1", true),
            httpRequest
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("errorCode", QueryExecutionPolicyException.EDITOR_MUTATION_FORBIDDEN);

        // Crucial: the analyzer must not have been called — otherwise the
        // DELETE would have executed via EXPLAIN ANALYZE.
        verify(explainPlanService, never()).analyzeQuery(anyString(), anyString(), anyBoolean());
        verify(sqlExecutionAuditService, times(1)).record(any());
    }

    @Test
    void useAnalyzeTrue_confirmationRequired_propagatesRequiresConfirmation() {
        givenConnection("conn-1", "postgres");
        when(queryExecutionPolicyService.enforce(any(), any(), eq("postgres")))
            .thenThrow(QueryExecutionPolicyException.confirmationRequired("UPDATE", java.util.List.of("verify rows")));

        ResponseEntity<?> response = controller.analyzeQuery(
            request("conn-1", "UPDATE users SET active=false WHERE id=1", true),
            httpRequest
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("requiresConfirmation", true);
        assertThat(body).containsEntry("queryType", "UPDATE");
        verify(explainPlanService, never()).analyzeQuery(anyString(), anyString(), anyBoolean());
    }

    @Test
    void useAnalyzeTrue_mcpBearer_usesMcpExecutionContext() {
        givenConnection("conn-1", "postgres");
        lenient().when(httpRequest.getHeader(anyString())).thenReturn(null);
        when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION))
            .thenReturn("Bearer dsql_mcp_public.secret");
        when(accessControlService.getCurrentUsername()).thenReturn("admin");
        when(accessControlService.isCurrentUserAdmin()).thenReturn(true);
        when(explainPlanService.analyzeQuery(eq("conn-1"), anyString(), eq(true)))
            .thenReturn(new ExplainPlanAnalysis());

        controller.analyzeQuery(
            request("conn-1", "CREATE TABLE t_new (id INT PRIMARY KEY)", true),
            httpRequest
        );

        ArgumentCaptor<QueryExecutionContext> captor = ArgumentCaptor.forClass(QueryExecutionContext.class);
        verify(queryExecutionPolicyService).enforce(any(), captor.capture(), eq("postgres"));
        assertThat(captor.getValue().origin()).isEqualTo(QueryExecutionOrigin.MCP);
        assertThat(captor.getValue().mutationMode())
            .isEqualTo(QueryExecutionContext.MutationMode.MAY_MUTATE);
        assertThat(captor.getValue().actorIsAdmin()).isTrue();
    }

    @Test
    void useAnalyzeFalse_skipsPolicyGate_butStillAudits() {
        // Plain EXPLAIN doesn't execute the query, so we don't need to gate
        // a developer running EXPLAIN over a DELETE — the database returns
        // a plan or an error, no data changes.
        when(explainPlanService.analyzeQuery(eq("conn-1"), anyString(), eq(false)))
            .thenReturn(new ExplainPlanAnalysis());

        ResponseEntity<?> response = controller.analyzeQuery(
            request("conn-1", "SELECT * FROM orders", false),
            httpRequest
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(queryExecutionPolicyService, never()).enforce(any(), any(), anyString());
        verify(credentialService, never()).getDecryptedConnection(anyString());
        verify(sqlExecutionAuditService, times(1)).record(any());
    }

    @Test
    void analyzerFailure_stillEmitsAuditFailureRow() {
        when(explainPlanService.analyzeQuery(eq("conn-1"), anyString(), eq(false)))
            .thenThrow(new RuntimeException("DB went away"));

        ResponseEntity<?> response = controller.analyzeQuery(
            request("conn-1", "SELECT * FROM orders", false),
            httpRequest
        );

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        verify(sqlExecutionAuditService, times(1)).record(any());
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private void givenConnection(String connectionId, String dbType) {
        ConnectionRequest conn = new ConnectionRequest();
        conn.setDbType(dbType);
        when(credentialService.getDecryptedConnection(connectionId)).thenReturn(conn);
        when(providerRegistry.getCanonicalName(dbType)).thenReturn(dbType);
    }

    private ExplainController.ExplainRequest request(String connectionId, String sql, boolean useAnalyze) {
        ExplainController.ExplainRequest req = new ExplainController.ExplainRequest();
        req.setConnectionId(connectionId);
        req.setQuery(sql);
        req.setUseAnalyze(useAnalyze);
        return req;
    }
}
