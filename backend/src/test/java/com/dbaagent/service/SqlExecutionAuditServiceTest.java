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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class SqlExecutionAuditServiceTest {

    @Mock private SecurityEventService securityEventService;
    @Mock private AccessControlService accessControlService;
    @Mock private UserRepository userRepository;
    @Mock private HttpServletRequest httpRequest;

    private SqlExecutionAuditService audit;

    @BeforeEach
    void setUp() {
        audit = new SqlExecutionAuditService(securityEventService, accessControlService, userRepository);
    }

    @Test
    void record_executed_logsSuccessEventWithClientMetadata() {
        givenActor("alice@example.com", 42L);

        ConnectionRequest conn = new ConnectionRequest();
        conn.setConnectionName("prod-pg");
        conn.setDbType("postgres");

        QueryRequest qr = new QueryRequest();
        qr.setQuery("SELECT 1");
        qr.setExecutionOrigin(QueryExecutionOrigin.EDITOR);
        qr.setLimit(100);
        qr.setTimeoutSeconds(30);

        QueryResult result = new QueryResult();
        result.setRowCount(1);
        result.setExecutionTimeMs(13L);

        audit.record(SqlExecutionAuditService.AuditRecord.executed()
            .connectionId("conn-1")
            .connectionRequest(conn)
            .queryRequest(qr)
            .queryResult(result)
            .httpRequest(httpRequest)
            .client(new ClientContext("mcp", "claude-code", "0.13.0")));

        SecurityEventService.EventRequest event = captureLoggedEvent();

        assertThat(event.eventType()).isEqualTo(SecurityEventType.EDITOR_QUERY_EXECUTED);
        assertThat(event.outcome()).isEqualTo(SecurityEventOutcome.SUCCESS);
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.email()).isEqualTo("alice@example.com");
        assertThat(event.targetResource()).isEqualTo("connection:conn-1");

        Map<String, Object> meta = event.metadata();
        assertThat(meta).containsEntry("clientType", "mcp");
        assertThat(meta).containsEntry("clientAgent", "claude-code");
        assertThat(meta).containsEntry("clientVersion", "0.13.0");
        assertThat(meta).containsEntry("operation", "execute");
        assertThat(meta).containsEntry("origin", "EDITOR");
        assertThat(meta).containsEntry("connectionId", "conn-1");
        assertThat(meta).containsEntry("connectionName", "prod-pg");
        assertThat(meta).containsEntry("dbType", "postgres");
        assertThat(meta).containsEntry("queryText", "SELECT 1");
        assertThat(meta).containsEntry("rowCount", 1);
        assertThat(meta.get("queryHash")).asString().hasSize(64); // sha256 hex
    }

    @Test
    void record_blocked_logsFailureWithReason() {
        givenActor("dev@example.com", 7L);

        QueryRequest qr = new QueryRequest();
        qr.setQuery("DELETE FROM users WHERE id = 1");
        qr.setExecutionOrigin(QueryExecutionOrigin.EDITOR);

        audit.record(SqlExecutionAuditService.AuditRecord.blocked("Only admins can execute DDL or DML.")
            .connectionId("conn-1")
            .queryRequest(qr)
            .client(new ClientContext("cli", "terminal", "0.13.0")));

        SecurityEventService.EventRequest event = captureLoggedEvent();
        assertThat(event.eventType()).isEqualTo(SecurityEventType.EDITOR_QUERY_BLOCKED);
        assertThat(event.outcome()).isEqualTo(SecurityEventOutcome.FAILURE);
        assertThat(event.reason()).contains("Only admins can execute DDL or DML.");
        assertThat(event.metadata()).containsEntry("clientType", "cli");
    }

    @Test
    void record_failed_capturesFailureReason() {
        givenActor("alice@example.com", 1L);

        QueryRequest qr = new QueryRequest();
        qr.setQuery("SELECT * FROM nonexistent");

        audit.record(SqlExecutionAuditService.AuditRecord.failed("Table 'nonexistent' not found")
            .connectionId("conn-1")
            .queryRequest(qr)
            .client(ClientContext.unknown()));

        SecurityEventService.EventRequest event = captureLoggedEvent();
        assertThat(event.eventType()).isEqualTo(SecurityEventType.EDITOR_QUERY_FAILED);
        assertThat(event.reason()).contains("Table 'nonexistent' not found");
        assertThat(event.metadata()).containsEntry("clientType", "unknown");
    }

    @Test
    void record_analyzePlan_carriesPlanSignalsInsteadOfRowCount() {
        givenActor("alice@example.com", 1L);

        QueryRequest qr = new QueryRequest();
        qr.setQuery("SELECT * FROM orders");

        ExplainPlanAnalysis analysis = new ExplainPlanAnalysis();
        analysis.setNodeCount(7);
        analysis.setTotalTimeMs(42.3);
        analysis.setWasExecuted(true);

        audit.record(SqlExecutionAuditService.AuditRecord.executed()
            .operation("analyze-plan")
            .connectionId("conn-1")
            .queryRequest(qr)
            .explainResult(analysis)
            .useAnalyze(true)
            .client(new ClientContext("mcp", "cursor", "0.13.0")));

        Map<String, Object> meta = captureLoggedEvent().metadata();
        assertThat(meta).containsEntry("operation", "analyze-plan");
        assertThat(meta).containsEntry("planNodeCount", 7);
        assertThat(meta).containsEntry("planTotalTimeMs", 42.3);
        assertThat(meta).containsEntry("explainExecutedTheQuery", true);
        assertThat(meta).containsEntry("useAnalyze", true);
        assertThat(meta).doesNotContainKey("rowCount");
    }

    @Test
    void record_truncatesOversizeQueryText() {
        givenActor("alice@example.com", 1L);

        QueryRequest qr = new QueryRequest();
        qr.setQuery("SELECT '" + "x".repeat(5000) + "'");

        audit.record(SqlExecutionAuditService.AuditRecord.executed()
            .connectionId("conn-1")
            .queryRequest(qr)
            .queryResult(new QueryResult())
            .client(ClientContext.unknown()));

        String stored = (String) captureLoggedEvent().metadata().get("queryText");
        assertThat(stored).hasSizeLessThanOrEqualTo(4001); // 4000 + the "…" marker
        assertThat(stored).endsWith("…");
    }

    @Test
    void record_swallowsAuditFailuresSoCallerCantSeeThem() {
        // Audit must never break a successful SQL execution. We simulate a
        // repo failure and assert the call returns cleanly.
        when(accessControlService.getCurrentUsername()).thenThrow(new RuntimeException("boom"));

        QueryRequest qr = new QueryRequest();
        qr.setQuery("SELECT 1");

        audit.record(SqlExecutionAuditService.AuditRecord.executed()
            .connectionId("conn-1")
            .queryRequest(qr)
            .client(ClientContext.unknown()));

        verify(securityEventService, never()).log(any());
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private void givenActor(String email, Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setUsername(email);
        when(accessControlService.getCurrentUsername()).thenReturn(email);
        when(userRepository.findByUsername(email)).thenReturn(Optional.of(user));
    }

    private SecurityEventService.EventRequest captureLoggedEvent() {
        ArgumentCaptor<SecurityEventService.EventRequest> captor =
            ArgumentCaptor.forClass(SecurityEventService.EventRequest.class);
        verify(securityEventService).log(captor.capture());
        return captor.getValue();
    }
}
