package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.mysql.MySQLQueryExecutionProvider;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Executor-side guardrails from a production incident, run against
 * the real MySQL provider so the SQL asserted here is the SQL that ships.
 */
@ExtendWith(MockitoExtension.class)
// The executor calls Statement.execute several times per run (preambles, guards, the
// statement); strict stubbing would reject every call but the one a test stubs.
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryExecutorGuardrailTest {

    // The mocked Connection has no metadata, so the server version is unknown (null).
    private static final String GUARD = new MySQLQueryExecutionProvider().statementGuardSql(240, null);
    private static final String RESET = new MySQLQueryExecutionProvider().statementGuardResetSql(null);

    @Mock private ConnectionService connectionService;
    @Mock private CredentialService credentialService;
    @Mock private SchemaSnapshotService schemaSnapshotService;
    @Mock private CacheManager cacheManager;
    @Mock private CacheMetricsService cacheMetricsService;
    @Mock private QueryLineageService queryLineageService;
    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private DatabaseDialect dialect;
    @Mock private QueryExecutionPolicyService queryExecutionPolicyService;
    @Mock private UserDataAccessPolicyService userDataAccessPolicyService;
    @Mock private com.dbaagent.service.telemetry.TelemetryCounters telemetryCounters;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    @Mock private Connection connection;
    @Mock private Statement statement;

    private QueryExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new QueryExecutorService(
            connectionService, credentialService, schemaSnapshotService, cacheManager, cacheMetricsService,
            queryLineageService, providerRegistry, queryExecutionPolicyService, userDataAccessPolicyService,
            telemetryCounters, keyColumnAnalysisRepository, new RunningQueryRegistry());

        ConnectionRequest request = new ConnectionRequest();
        request.setDbType("mysql");
        when(credentialService.getDecryptedConnection("conn-1")).thenReturn(request);
        lenient().when(providerRegistry.getCanonicalName("mysql")).thenReturn("mysql");
        when(providerRegistry.getDialect("mysql")).thenReturn(dialect);
        when(dialect.queryExecution()).thenReturn(new MySQLQueryExecutionProvider());
        when(connectionService.getConnection("conn-1", request)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        // The session-pid probe is best effort; failing it here keeps it out of the way.
        lenient().when(statement.executeQuery("SELECT CONNECTION_ID()")).thenThrow(new SQLException("no pid"));
        lenient().when(userDataAccessPolicyService.redactResult(any(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(1));
    }

    private void policyReturns(boolean mutating, String queryType) {
        when(queryExecutionPolicyService.enforce(any(), any(), eq("mysql")))
            .thenReturn(new QueryExecutionPolicyService.PolicyDecision(List.of(), mutating, queryType));
    }

    private QueryExecutionPolicyException runExpectingPolicyError(String sql) {
        return assertThrows(QueryExecutionPolicyException.class, () -> executor.executeQuery(
            "conn-1", new QueryRequest(sql, null, 240), QueryExecutionContext.editor("dba", true, true)));
    }

    @Test
    void guardsAreReappliedAfterTheUsersPreamblesAndBeforeTheStatement() throws Exception {
        policyReturns(true, "UPDATE");

        executor.executeQuery("conn-1",
            new QueryRequest("SET SESSION lock_wait_timeout = 31536000; UPDATE customers SET contact = NULL WHERE id = 7",
                null, 240),
            QueryExecutionContext.editor("dba", true, true));

        String tag = "/* deepsql origin=editor user=dba */ ";
        InOrder order = inOrder(statement);
        order.verify(statement).execute(GUARD);
        order.verify(statement).execute(tag + "SET SESSION lock_wait_timeout = 31536000");
        order.verify(statement).execute(GUARD);
        order.verify(statement).execute(tag + "UPDATE customers SET contact = NULL WHERE id = 7");
        order.verify(statement).execute(RESET);
    }

    @Test
    void aHeavyReadPlacedFirstInABatchStillRunsUnderTheGuards() throws Exception {
        // A read-only batch may hold several SELECTs; the first used to run as a preamble
        // with no read time limit and no actor tag.
        policyReturns(false, "SELECT");

        executor.executeQuery("conn-1",
            new QueryRequest("SELECT * FROM bookings b JOIN payments p ON p.booking_id = b.id; SELECT 1", null, 240),
            QueryExecutionContext.editor("dba", true, false));

        InOrder order = inOrder(statement);
        order.verify(statement).execute(GUARD);
        order.verify(statement).execute(
            "/* deepsql origin=editor user=dba */ SELECT * FROM bookings b JOIN payments p ON p.booking_id = b.id");
    }

    @Test
    void theSessionIsResetEvenWhenTheStatementFails() throws Exception {
        // Session variables outlive the borrow: without the reset, this run's
        // sql_safe_updates and max_execution_time would reach the next borrower,
        // background jobs on the same pool included.
        policyReturns(true, "UPDATE");
        when(statement.execute(startsWith("/* deepsql"))).thenThrow(new SQLException("Duplicate entry", "23000", 1062));

        assertThrows(SQLException.class, () -> executor.executeQuery("conn-1",
            new QueryRequest("UPDATE customers SET email = 'a@b.c' WHERE id = 7", null, 240),
            QueryExecutionContext.editor("dba", true, true)));

        verify(statement).execute(RESET);
    }

    @Test
    void aStatementDoesNotRunWithoutItsGuards() throws Exception {
        policyReturns(false, "SELECT");
        when(statement.execute(GUARD)).thenThrow(new SQLException("Unknown system variable", "HY000", 1193));

        SQLException e = assertThrows(SQLException.class, () -> executor.executeQuery(
            "conn-1", new QueryRequest("SELECT 1", null, 240), QueryExecutionContext.editor("dba", true, false)));

        assertThat(e.getMessage()).contains("safety settings");
        verify(statement, never()).execute(startsWith("/* deepsql"));
    }

    @Test
    void anAlterFallsBackFromInstantToInplaceWithoutLocks() throws Exception {
        policyReturns(true, "ALTER");
        String alter = "ALTER TABLE customers MODIFY email varchar(512) NOT NULL";
        String tag = "/* deepsql origin=editor user=dba */ ";
        when(statement.execute(tag + alter + "\n, ALGORITHM=INSTANT")).thenThrow(new SQLException(
            "ALGORITHM=INSTANT is not supported. Reason: Need to rebuild the table. Try ALGORITHM=COPY/INPLACE.",
            "0A000", 1846));

        executor.executeQuery("conn-1", new QueryRequest(alter, null, 240),
            QueryExecutionContext.editor("dba", true, true));

        verify(statement).execute(tag + alter + "\n, ALGORITHM=INPLACE, LOCK=NONE");
    }

    @Test
    void anAlterThatCannotRunOnlineChangesNothingAndSaysWhy() throws Exception {
        policyReturns(true, "ALTER");
        when(statement.execute(startsWith("/* deepsql"))).thenThrow(new SQLException(
            "ALGORITHM=INPLACE is not supported. Reason: Cannot change column type INPLACE. Try ALGORITHM=COPY.",
            "0A000", 1846));

        QueryExecutionPolicyException e = runExpectingPolicyError("ALTER TABLE customers MODIFY id BIGINT");

        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.DB_GUARDRAIL_TRIPPED);
        assertThat(e.getMessage()).contains("gh-ost");
    }

    @Test
    void aLockWaitTimeoutIsReportedAsTheGuardItIs() throws Exception {
        policyReturns(true, "ALTER");
        when(statement.execute(startsWith("/* deepsql"))).thenThrow(new SQLException(
            "Lock wait timeout exceeded; try restarting transaction", "HY000", 1205));

        QueryExecutionPolicyException e = runExpectingPolicyError("ALTER TABLE customers ADD COLUMN x INT");

        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.DB_GUARDRAIL_TRIPPED);
        assertThat(e.getMessage()).contains("5 seconds");
        // Not retried as INPLACE: a busy table is busy for every algorithm.
        verify(statement, never()).execute(org.mockito.ArgumentMatchers.endsWith("LOCK=NONE"));
    }

    @Test
    void peopleAndAgentsGetAtMostTenThousandRowsWhateverTheyAskFor() {
        assertThat(QueryExecutorService.effectiveRowLimit(null, QueryExecutionContext.editor("a", true, false)))
            .isEqualTo(10_000);
        assertThat(QueryExecutorService.effectiveRowLimit(50_000, QueryExecutionContext.mcp("a")))
            .isEqualTo(10_000);
        assertThat(QueryExecutorService.effectiveRowLimit(1_000, QueryExecutionContext.editor("a", false, false)))
            .isEqualTo(1_000);
        assertThat(QueryExecutorService.effectiveRowLimit(null, QueryExecutionContext.api("a")))
            .isEqualTo(10_000);
        // DeepSQL's own jobs keep what they ask for.
        assertThat(QueryExecutorService.effectiveRowLimit(null, QueryExecutionContext.internal())).isZero();
        assertThat(QueryExecutorService.effectiveRowLimit(null, QueryExecutionContext.scheduled())).isZero();
    }

    @Test
    void theActorTagCannotBeUsedToCloseTheComment() {
        String tag = QueryExecutorService.actorTag(QueryExecutionContext.editor("x */ DROP TABLE t; /*", true, true));
        assertThat(tag.indexOf("*/")).isEqualTo(tag.length() - 3);
        assertThat(tag).isEqualTo("/* deepsql origin=editor user=x____DROP_TABLE_t____ */ ");
    }

    @Test
    void readsAreUnaffectedBeyondTheTagAndTheCap() throws Exception {
        policyReturns(false, "SELECT");
        when(statement.execute(anyString())).thenReturn(false);

        executor.executeQuery("conn-1", new QueryRequest("SELECT * FROM customers", null, 240),
            QueryExecutionContext.mcp("agent"));

        // The cap is setMaxRows only. Appending LIMIT text to a request that asked for no
        // limit broke FETCH FIRST, FOR UPDATE and LOCK IN SHARE MODE.
        verify(statement).setMaxRows(10_001);
        verify(statement).execute("/* deepsql origin=mcp user=agent */ SELECT * FROM customers");
    }
}
