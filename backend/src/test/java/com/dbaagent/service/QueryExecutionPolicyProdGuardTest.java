package com.dbaagent.service;

import com.dbaagent.model.Permission;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.mysql.MySQLQueryExecutionProvider;
import com.dbaagent.service.security.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Production guardrails, exercised against the real MySQL provider. Every case runs
 * as a confirmed admin: these are the statements that must stay blocked even for
 * the people who can otherwise write.
 */
@ExtendWith(MockitoExtension.class)
class QueryExecutionPolicyProdGuardTest {

    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private DatabaseDialect databaseDialect;
    @Mock private AccessControlService accessControlService;

    private QueryExecutionPolicyService service;

    @BeforeEach
    void setUp() {
        lenient().when(providerRegistry.getDialect(anyString())).thenReturn(databaseDialect);
        lenient().when(databaseDialect.queryExecution()).thenReturn(new MySQLQueryExecutionProvider());
        lenient().when(providerRegistry.getCanonicalName(anyString())).thenReturn("mysql");
        lenient().when(accessControlService.hasPermission(Permission.EXECUTE_DDL)).thenReturn(true);
        service = new QueryExecutionPolicyService(providerRegistry, accessControlService);
    }

    private QueryExecutionPolicyException blocked(String sql, QueryExecutionContext context) {
        return assertThrows(QueryExecutionPolicyException.class,
            () -> service.enforce(new QueryRequest(sql, null, null), context, "mysql"), sql);
    }

    private static QueryExecutionContext confirmedAdminEditor() {
        return QueryExecutionContext.editor("admin", true, true);
    }

    @Test
    void administrativeStatementsAreBlockedOnEverySurfaceEvenWhenConfirmed() {
        for (String sql : List.of(
            "SET GLOBAL max_connections = 10",
            "SET PERSIST innodb_lock_wait_timeout = 1",
            "SET @@global.read_only = 0",
            "SET @x = 1, GLOBAL max_connections = 10",
            "SET sql_log_bin = 0",
            "GRANT ALL ON *.* TO 'x'@'%'",
            "REVOKE SELECT ON db.* FROM 'x'@'%'",
            "CREATE USER 'x'@'%' IDENTIFIED BY 'p'",
            "ALTER USER 'app_user'@'%' IDENTIFIED BY 'p'",
            "DROP USER 'x'@'%'",
            "KILL 12345",
            "KILL QUERY 12345",
            "LOCK TABLES customers WRITE",
            "FLUSH TABLES WITH READ LOCK",
            "SHUTDOWN",
            "RESET MASTER",
            "PURGE BINARY LOGS BEFORE NOW()",
            // Postgres role/identity switches: left on a pooled connection, inherited by the next borrower.
            "SET SESSION ROLE app_owner",
            "SET LOCAL ROLE app_owner",
            "SET SESSION AUTHORIZATION app_owner",
            "SET role = app_owner",
            "SET LOCAL role TO app_owner",
            "SET session_replication_role = replica",
            "  /* sneaky */ GRANT ALL ON *.* TO 'x'@'%'")) {
            assertThat(blocked(sql, confirmedAdminEditor()).getErrorCode())
                .as(sql).isEqualTo(QueryExecutionPolicyException.ADMIN_STATEMENT_BLOCKED);
            assertThat(blocked(sql, QueryExecutionContext.mcp("admin", true, true)).getErrorCode())
                .as(sql).isEqualTo(QueryExecutionPolicyException.ADMIN_STATEMENT_BLOCKED);
        }
    }

    @Test
    void executableCommentsAndOptimizerHintsAreRefused() {
        // MySQL runs the body of /*! ... */, while every check here strips it as a comment
        // and then sees an empty, "read-only" statement. /*+ ... */ hints such as
        // MAX_EXECUTION_TIME or SET_VAR override the session guards for one statement.
        for (String sql : List.of(
            "/*!50000 GRANT ALL ON *.* TO 'x'@'%' */",
            "/*! ALTER TABLE customers ADD COLUMN x INT */",
            "/*!SET GLOBAL max_connections = 10*/",
            // MariaDB's form: MySQL ignores it, MariaDB runs it.
            "/*M!100000 GRANT ALL ON *.* TO 'x'@'%' */",
            "/*m!100000 DROP TABLE customers */",
            "SELECT /*+ MAX_EXECUTION_TIME(600000) */ * FROM customers",
            "UPDATE /*+ SET_VAR(innodb_lock_wait_timeout=31536000) */ customers SET a = 1 WHERE id = 2")) {
            assertThat(blocked(sql, QueryExecutionContext.editor("analyst", false, false)).getErrorCode())
                .as(sql).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
            assertThat(blocked(sql, confirmedAdminEditor()).getErrorCode())
                .as(sql).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        }
    }

    @Test
    void globalScopeCannotHideInsideMismatchedQuotes() {
        // Two-pass quote stripping removed GLOBAL here; MySQL tokenizes it as code. And a
        // NO_BACKSLASH_ESCAPES preamble changes MySQL's tokenizing, so no scanner on our
        // side can be trusted to agree: the SET check reads the raw text and fails closed.
        for (String sql : List.of(
            "SET @a = \"'\", GLOBAL max_connections = 1, @b = \"'\"",
            "SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES'; "
                + "SET @a = '\\', GLOBAL max_connections = 1, @b = '\\'; SELECT 1")) {
            assertThat(blocked(sql, QueryExecutionContext.editor("analyst", false, false)).getErrorCode())
                .as(sql).isEqualTo(QueryExecutionPolicyException.ADMIN_STATEMENT_BLOCKED);
        }
    }

    @Test
    void schemaChangesThatDoNotStartWithADdlVerbStillNeedThePermission() {
        lenient().when(accessControlService.hasPermission(Permission.EXECUTE_DDL)).thenReturn(false);
        for (String sql : List.of(
            "CALL rebuild_customer_indexes()",
            "SELECT * INTO customers_copy FROM customers",
            // Postgres runs the wrapped statement under EXPLAIN ANALYZE: this creates a table.
            "EXPLAIN ANALYZE CREATE TABLE customers_copy AS SELECT * FROM customers",
            "DO RELEASE_LOCK('x')")) {
            assertThat(blocked(sql, QueryExecutionContext.editor("dba", true, true)).getErrorCode())
                .as(sql).isEqualTo(QueryExecutionPolicyException.DDL_PERMISSION_REQUIRED);
        }
    }

    @Test
    void anAdministrativeStatementHiddenAsAPreambleIsStillBlocked() {
        // SET used to be classified as a harmless session preamble, so a SET GLOBAL in
        // front of a SELECT ran for any role, including read-only ones.
        assertThat(blocked("SET GLOBAL max_connections = 10; SELECT 1",
            QueryExecutionContext.editor("analyst", false, false)).getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.ADMIN_STATEMENT_BLOCKED);
    }

    @Test
    void functionsThatReachOutsideTheQueryAreRefusedInTheEditorToo() {
        // The denylist used to run only for MCP and dashboards; in the Editor these were plain reads.
        for (String sql : List.of(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'app'",
            "SELECT pg_cancel_backend(12345)",
            "SELECT dblink_exec('dbname=app', 'DELETE FROM orders')")) {
            assertThat(blocked(sql, QueryExecutionContext.editor("analyst", false, false)).getErrorCode())
                .as(sql).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        }
    }

    @Test
    void aUserVariableNamedRoleIsNotARoleSwitch() {
        service.enforce(new QueryRequest("SET @role = 1; SELECT @role", null, null),
            QueryExecutionContext.editor("analyst", false, false), "mysql");
    }

    @Test
    void sessionVariablesStillWork() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("SET @since = '2026-11-01'; SELECT * FROM bookings WHERE created_at > @since", null, null),
            QueryExecutionContext.editor("analyst", false, false),
            "mysql");
        assertThat(decision.mutating()).isFalse();
    }

    @Test
    void identifiersThatLookLikeAdminVerbsAreNotBlocked() {
        // Same class of mistake as the old \bCOMMENT\b rule rejecting SELECT * FROM comment.
        service.enforce(new QueryRequest("SELECT grant_id, kill_switch FROM `lock`", null, null),
            QueryExecutionContext.editor("analyst", false, false), "mysql");
    }

    @Test
    void truncateIsBlockedInTheEditorEvenForAConfirmedAdmin() {
        assertThat(blocked("TRUNCATE TABLE customers", confirmedAdminEditor()).getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
    }

    @Test
    void ddlRequiresTheExecuteDdlPermission() {
        when(accessControlService.hasPermission(Permission.EXECUTE_DDL)).thenReturn(false);
        QueryExecutionContext dba = QueryExecutionContext.editor("dba", true, true);

        for (String sql : List.of(
            "ALTER TABLE customers MODIFY email varchar(512) NOT NULL",
            "CREATE TABLE t (id INT)",
            "CREATE INDEX i ON t (c)",
            "DROP INDEX i ON t",
            "RENAME TABLE a TO b")) {
            assertThat(blocked(sql, dba).getErrorCode()).as(sql)
                .isEqualTo(QueryExecutionPolicyException.DDL_PERMISSION_REQUIRED);
            // MCP refuses every DROP outright, ahead of the permission check.
            String mcpCode = sql.startsWith("DROP")
                ? QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED
                : QueryExecutionPolicyException.DDL_PERMISSION_REQUIRED;
            assertThat(blocked(sql, QueryExecutionContext.mcp("dba", true, true)).getErrorCode()).as(sql)
                .isEqualTo(mcpCode);
        }
    }

    @Test
    void ddlCannotHideBehindAPreambleToSkipThePermissionCheck() {
        // The DDL check reads the first statement. That is only sound because a run
        // containing a mutation is limited to one statement; this pins that invariant.
        lenient().when(accessControlService.hasPermission(Permission.EXECUTE_DDL)).thenReturn(false);
        for (String sql : List.of(
            "SET @x = 1; ALTER TABLE customers MODIFY email varchar(512) NOT NULL",
            "SELECT 1; ALTER TABLE customers MODIFY email varchar(512) NOT NULL",
            "SET @x = 1; TRUNCATE TABLE customers")) {
            QueryExecutionPolicyException e = blocked(sql, QueryExecutionContext.editor("dba", true, true));
            assertThat(e.getErrorCode()).as(sql).isIn(
                QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED,
                QueryExecutionPolicyException.DDL_PERMISSION_REQUIRED);
        }
    }

    @Test
    void readOnlySurfacesCannotRunDdlAtAll() {
        // API (dashboards) and chat are READ_ONLY_ONLY, so no permission check is needed there.
        assertThat(blocked("ALTER TABLE customers ADD COLUMN x INT", QueryExecutionContext.api("admin", true))
            .getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_FORBIDDEN);
        assertThat(blocked("ALTER TABLE customers ADD COLUMN x INT", QueryExecutionContext.chat())
            .getErrorCode()).isEqualTo(QueryExecutionPolicyException.CHAT_MUTATION_BLOCKED);
    }

    @Test
    void aDataModifyingCteIsStillRowOnly() {
        lenient().when(accessControlService.hasPermission(Permission.EXECUTE_DDL)).thenReturn(false);
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("WITH stale AS (SELECT id FROM customers WHERE active = 0) "
                + "DELETE FROM customers WHERE id IN (SELECT id FROM stale)", null, null),
            QueryExecutionContext.editor("dba", true, true), "mysql");
        assertThat(decision.mutating()).isTrue();
    }

    @Test
    void aWriteInsideACteIsStillRowOnly() {
        // Classified as "DELETE (in CTE)"; the label must not read as a schema change.
        lenient().when(accessControlService.hasPermission(Permission.EXECUTE_DDL)).thenReturn(false);
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("WITH d AS (DELETE FROM customers WHERE id = 5 RETURNING *) SELECT * FROM d", null, null),
            QueryExecutionContext.editor("dba", true, true), "mysql");
        assertThat(decision.mutating()).isTrue();
    }

    @Test
    void dmlDoesNotNeedTheDdlPermission() {
        lenient().when(accessControlService.hasPermission(Permission.EXECUTE_DDL)).thenReturn(false);
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("UPDATE customers SET contact = NULL WHERE id = 7", null, null),
            QueryExecutionContext.editor("dba", true, true), "mysql");
        assertThat(decision.mutating()).isTrue();
    }

    @Test
    void aBlockingAlterIsRefusedBeforeTheConfirmationPrompt() {
        // Unconfirmed: the refusal must come instead of the "are you sure?" dialog, not after it.
        QueryExecutionPolicyException e = blocked(
            "ALTER TABLE customers MODIFY email varchar(512) NOT NULL, ALGORITHM=COPY",
            QueryExecutionContext.editor("admin", true, false));
        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(e.getMessage()).contains("gh-ost");
    }

    @Test
    void theAlterConfirmationSaysHowItWillRun() {
        QueryExecutionPolicyException e = blocked(
            "ALTER TABLE customers MODIFY email varchar(512) NOT NULL",
            QueryExecutionContext.editor("admin", true, false));
        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_CONFIRMATION_REQUIRED);
        // Dialect-neutral wording: the policy no longer names MySQL algorithms.
        assertThat(String.join(" ", e.getWarnings())).contains("does not block writes").contains("5 seconds");
    }
}
