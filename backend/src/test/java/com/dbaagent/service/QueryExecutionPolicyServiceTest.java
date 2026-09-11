package com.dbaagent.service;

import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.QueryExecutionProvider;
import com.dbaagent.provider.mysql.MySQLQueryExecutionProvider;
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

@ExtendWith(MockitoExtension.class)
class QueryExecutionPolicyServiceTest {

    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private DatabaseDialect databaseDialect;

    private QueryExecutionPolicyService service;

    @BeforeEach
    void setUp() {
        // The real provider, not a stub. A stubbed isReadOnlyQuery() that always
        // answered "false" made these tests assert the opposite of production:
        // MySQLQueryExecutionProvider reports anything starting with WITH as
        // read-only, which is how `WITH x AS (DELETE ...) SELECT` reached the
        // database classified as a read.
        QueryExecutionProvider realProvider = new MySQLQueryExecutionProvider();
        when(providerRegistry.getDialect(anyString())).thenReturn(databaseDialect);
        when(databaseDialect.queryExecution()).thenReturn(realProvider);
        lenient().when(providerRegistry.getCanonicalName(anyString())).thenReturn("mysql");
        service = new QueryExecutionPolicyService(providerRegistry);
    }

    @Test
    void chatSelect_isAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("SELECT 1", 10, 30),
            QueryExecutionContext.chat(),
            "mysql"
        );

        assertThat(decision.mutating()).isFalse();
        assertThat(decision.primaryQueryType()).isEqualTo("SELECT");
    }

    @Test
    void chatMutation_isBlockedForAllUsers() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DELETE FROM bookings WHERE id = 1", null, null),
                QueryExecutionContext.chat(),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.CHAT_MUTATION_BLOCKED);
    }

    @Test
    void editorMutation_nonAdminIsBlocked() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("INSERT INTO audit_log(id) VALUES (1)", null, null),
                QueryExecutionContext.editor("analyst", false, false),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_FORBIDDEN);
    }

    @Test
    void editorMutation_adminRequiresConfirmation() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("UPDATE customers SET property_status = 'ACTIVE' WHERE customer_id = 9", null, null),
                QueryExecutionContext.editor("admin", true, false),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_CONFIRMATION_REQUIRED);
        assertThat(exception.isRequiresConfirmation()).isTrue();
        assertThat(exception.getWarnings()).isNotEmpty();
    }

    @Test
    void editorMutation_dbaRequiresConfirmation() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("UPDATE customers SET property_status = 'ACTIVE' WHERE customer_id = 9", null, null),
                QueryExecutionContext.editor("dba", true, false),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_CONFIRMATION_REQUIRED);
        assertThat(exception.isRequiresConfirmation()).isTrue();
    }

    @Test
    void editorMutation_dbaConfirmedIsAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("UPDATE customers SET property_status = 'ACTIVE' WHERE customer_id = 9", null, null),
            QueryExecutionContext.editor("dba", true, true),
            "mysql"
        );

        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).isEqualTo("UPDATE");
    }

    @Test
    void editorMutation_developerStillForbidden() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DELETE FROM bookings WHERE id = 1", null, null),
                QueryExecutionContext.editor("developer", false, true),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_FORBIDDEN);
        assertThat(exception.getMessage()).contains("admins or DBAs");
    }

    @Test
    void editorConfirmedDeleteWithoutWhere_isBlocked() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DELETE FROM customers", null, null),
                QueryExecutionContext.editor("admin", true, true),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("without a WHERE clause");
    }

    // EXPLAIN UPDATE/DELETE is the one reachable path to the keyword-fallback
    // containsWhereClause check for a real UPDATE/DELETE: JSqlParser only models
    // EXPLAIN SELECT, so these fall through to detectExplainWrappedMutation
    // rather than Update.getWhere()/Delete.getWhere(), which every ordinary
    // (non-EXPLAIN) mutation test above exercises instead.
    @Test
    void editorConfirmedExplainUpdateWithMultilineWhere_isAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest(
                "EXPLAIN UPDATE customers\nSET property_status = 'ACTIVE'\nWHERE customer_id = 9",
                null,
                null
            ),
            QueryExecutionContext.editor("admin", true, true),
            "mysql"
        );

        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).isEqualTo("UPDATE");
    }

    @Test
    void editorConfirmedExplainDeleteWithOnlyCommentedOutWhere_isBlocked() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("EXPLAIN DELETE FROM customers -- WHERE customer_id = 9\n", null, null),
                QueryExecutionContext.editor("admin", true, true),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("without a WHERE clause");
    }

    @Test
    void editorMutation_multiStatementBatchIsBlocked() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("UPDATE customers SET property_status = 'ACTIVE' WHERE customer_id = 9; DELETE FROM customers WHERE customer_id = 10", null, null),
                QueryExecutionContext.editor("admin", true, true),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("single DDL or DML statement");
    }

    @Test
    void readOnlyMultiStatementWithUsePreamble_isAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("USE analytics; SELECT 1", null, null),
            QueryExecutionContext.editor("analyst", false, false),
            "mysql"
        );

        assertThat(decision.mutating()).isFalse();
        assertThat(decision.classifications()).hasSize(2);
        assertThat(decision.classifications().get(0).sessionPreamble()).isTrue();
        assertThat(decision.classifications().get(1).readOnly()).isTrue();
    }

    @Test
    void explainWrappedUpdate_isTreatedAsMutation() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("EXPLAIN UPDATE customers SET property_status = 'ACTIVE' WHERE customer_id = 9", null, null),
                QueryExecutionContext.chat(),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.CHAT_MUTATION_BLOCKED);
    }

    @Test
    void withInsert_isTreatedAsMutation() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest(
                    "WITH src AS (SELECT 1 AS id) INSERT INTO audit_log(id) SELECT id FROM src",
                    null,
                    null
                ),
                QueryExecutionContext.chat(),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.CHAT_MUTATION_BLOCKED);
    }

    @Test
    void editorConfirmedDeleteWithWhere_isAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("DELETE FROM customers WHERE customer_id = 9", null, null),
            QueryExecutionContext.editor("admin", true, true),
            "mysql"
        );

        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).isEqualTo("DELETE");
    }

    @Test
    void editorDropTable_isBlockedEvenForAdmin() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DROP TABLE temp_rollup", null, null),
                QueryExecutionContext.editor("admin", true, true),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("DROP TABLE");
    }

    @Test
    void editorDropTableIfExists_isAlsoBlocked() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DROP TABLE IF EXISTS temp_rollup", null, null),
                QueryExecutionContext.editor("admin", true, true),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("DROP TABLE");
    }

    @Test
    void editorDropIndex_isAllowedForConfirmedAdmin() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("DROP INDEX idx_bookings_hotel ON bookings", null, null),
            QueryExecutionContext.editor("admin", true, true),
            "mysql"
        );

        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).startsWith("DROP");
        assertThat(decision.primaryQueryType()).doesNotContain("TABLE");
    }

    @Test
    void editorDropView_isAllowedForConfirmedAdmin() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("DROP VIEW v_active_hotels", null, null),
            QueryExecutionContext.editor("admin", true, true),
            "mysql"
        );

        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).startsWith("DROP");
        assertThat(decision.primaryQueryType()).doesNotContain("TABLE");
    }

    @Test
    void editorDropIndex_unconfirmedAdmin_requiresConfirmation() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DROP INDEX idx_bookings_hotel ON bookings", null, null),
                QueryExecutionContext.editor("admin", true, false),
                "mysql"
            )
        );

        // Other DROPs still flow through the standard mutation-confirmation gate.
        assertThat(exception.getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_CONFIRMATION_REQUIRED);
        assertThat(exception.isRequiresConfirmation()).isTrue();
    }

    @Test
    void editorAlterAndCreate_areAllowedForConfirmedAdmin() {
        QueryExecutionPolicyService.PolicyDecision createDecision = service.enforce(
            new QueryRequest("CREATE TABLE t_new (id INT PRIMARY KEY)", null, null),
            QueryExecutionContext.editor("admin", true, true),
            "mysql"
        );
        assertThat(createDecision.mutating()).isTrue();
        assertThat(createDecision.primaryQueryType()).isEqualTo("CREATE");

        QueryExecutionPolicyService.PolicyDecision alterDecision = service.enforce(
            new QueryRequest("ALTER TABLE customers ADD COLUMN tag VARCHAR(64)", null, null),
            QueryExecutionContext.editor("admin", true, true),
            "mysql"
        );
        assertThat(alterDecision.mutating()).isTrue();
        assertThat(alterDecision.primaryQueryType()).isEqualTo("ALTER");
    }

    @Test
    void internalDropTable_isStillAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("DROP TABLE temp_rollup", null, null),
            QueryExecutionContext.internal(),
            "mysql"
        );

        assertThat(decision.mutating()).isTrue();
        // Internal contexts (background jobs, migrations) bypass the DROP TABLE editor gate.
        assertThat(decision.primaryQueryType()).isEqualTo("DROP TABLE");
    }

    @Test
    void internalMutation_isStillAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("TRUNCATE TABLE temp_rollup", null, null),
            QueryExecutionContext.internal(),
            "mysql"
        );

        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).isEqualTo("TRUNCATE");
    }

    // --- Writes hidden inside a statement that reads as a SELECT ---------------
    // PostgreSQL executes data-modifying CTEs, and the parser models them as a
    // Select. Each of these deleted or rewrote a whole table from a non-admin
    // account before the classifier learned to look inside.

    private QueryExecutionPolicyException assertBlockedForViewer(String sql) {
        return assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest(sql, null, null),
                QueryExecutionContext.editor("viewer", false, false),
                "postgresql"
            )
        );
    }

    @Test
    void cteDelete_isBlockedForNonAdmin() {
        QueryExecutionPolicyException e =
            assertBlockedForViewer("WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x");
        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_FORBIDDEN);
    }

    @Test
    void cteUpdate_isBlockedForNonAdmin() {
        assertBlockedForViewer("WITH u AS (UPDATE orders SET total = 0 RETURNING *) SELECT * FROM u");
    }

    @Test
    void cteInsert_isBlockedForNonAdmin() {
        assertBlockedForViewer("WITH i AS (INSERT INTO audit(id) VALUES (1) RETURNING *) SELECT * FROM i");
    }

    @Test
    void cteWriteInLaterPosition_isBlockedForNonAdmin() {
        assertBlockedForViewer(
            "WITH a AS (SELECT 1), b AS (DELETE FROM orders RETURNING *) SELECT * FROM a");
    }

    @Test
    void nestedCteWrite_isBlockedForNonAdmin() {
        assertBlockedForViewer(
            "WITH o AS (WITH i AS (DELETE FROM orders RETURNING *) SELECT * FROM i) SELECT * FROM o");
    }

    @Test
    void selectInto_isBlockedForNonAdmin() {
        assertBlockedForViewer("SELECT * INTO exfiltrated FROM customers");
    }

    @Test
    void cteWrite_requiresConfirmationForAdmin() {
        QueryExecutionPolicyException e = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x", null, null),
                QueryExecutionContext.editor("admin", true, false),
                "postgresql"
            )
        );
        assertThat(e.getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_CONFIRMATION_REQUIRED);
    }

    @Test
    void cteWrite_isAllowedForConfirmedAdmin() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x", null, null),
            QueryExecutionContext.editor("admin", true, true),
            "postgresql"
        );
        assertThat(decision.mutating()).isTrue();
    }

    // --- The guard must not swallow legitimate reads --------------------------

    @Test
    void readOnlyCte_remainsAllowedForNonAdmin() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("WITH recent AS (SELECT * FROM orders LIMIT 10) SELECT * FROM recent", null, null),
            QueryExecutionContext.editor("viewer", false, false),
            "postgresql"
        );
        assertThat(decision.mutating()).isFalse();
        assertThat(decision.primaryQueryType()).isEqualTo("SELECT");
    }

    @Test
    void writeKeywordInsideStringLiteral_isStillAReadForNonAdmin() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("SELECT 'WITH x AS (DELETE FROM t)' AS example", null, null),
            QueryExecutionContext.editor("viewer", false, false),
            "postgresql"
        );
        assertThat(decision.mutating()).isFalse();
    }

    @Test
    void writeKeywordInsideComment_isStillAReadForNonAdmin() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("SELECT 1 -- WITH x AS (DELETE FROM t)\n", null, null),
            QueryExecutionContext.editor("viewer", false, false),
            "postgresql"
        );
        assertThat(decision.mutating()).isFalse();
    }

    @Test
    void hiddenWriteScan_staysLinearOnAdversarialInput() {
        // The text backstop used a lazy wildcard (\bSELECT\b[\s\S]*?\bINTO)
        // and a regex block-comment strip, both of which backtracked
        // quadratically: 224KB of repeated "SELECT " burned ~44s of CPU inside
        // the guard, before the query ever reached the database. Any
        // authenticated Editor user could stall a request thread with it.
        String repeatedSelect = "SELECT " + "SELECT ".repeat(32_000);
        String unterminatedBlockComment = "SELECT 1 /*" + "a/*".repeat(32_000);

        for (String hostile : List.of(repeatedSelect, unterminatedBlockComment)) {
            long startedAt = System.currentTimeMillis();
            service.enforce(
                new QueryRequest(hostile, null, null),
                QueryExecutionContext.editor("viewer", false, false),
                "postgresql"
            );
            long elapsed = System.currentTimeMillis() - startedAt;
            assertThat(elapsed)
                .as("classification of a %d char statement must not backtrack", hostile.length())
                .isLessThan(5_000L);
        }
    }

    @Test
    void insertIntoSelect_isNotMisreadAsSelectInto() {
        // SELECT_INTO_PATTERN matches a bare INTO target now that the SELECT
        // prefix is gone, so the caller must gate it on the statement actually
        // reading as a SELECT.
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("INSERT INTO archive SELECT * FROM orders", null, null),
            QueryExecutionContext.editor("admin", true, true),
            "postgresql"
        );
        assertThat(decision.primaryQueryType()).isEqualTo("INSERT");
    }

    @Test
    void insertIntoSelect_isStillClassifiedAsInsert() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("INSERT INTO archive SELECT * FROM orders", null, null),
            QueryExecutionContext.editor("admin", true, true),
            "postgresql"
        );
        assertThat(decision.primaryQueryType()).isEqualTo("INSERT");
    }

    // --- MCP / coding-agent surface: CREATE/ALTER go through; DROP/TRUNCATE do not ---

    @Test
    void mcpAdminCreate_unconfirmedRequiresConfirmation() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("CREATE TABLE t_new (id INT PRIMARY KEY)", null, null),
                QueryExecutionContext.mcp("admin", true, false),
                "mysql"
            )
        );
        assertThat(exception.getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_CONFIRMATION_REQUIRED);
        assertThat(exception.isRequiresConfirmation()).isTrue();
    }

    @Test
    void mcpAdminCreate_confirmedIsAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("CREATE TABLE t_new (id INT PRIMARY KEY)", null, null),
            QueryExecutionContext.mcp("admin", true, true),
            "mysql"
        );
        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).isEqualTo("CREATE");
    }

    @Test
    void mcpAdminAlter_confirmedIsAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("ALTER TABLE customers ADD COLUMN tag VARCHAR(64)", null, null),
            QueryExecutionContext.mcp("admin", true, true),
            "mysql"
        );
        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).isEqualTo("ALTER");
    }

    @Test
    void mcpAdminCreateIndex_confirmedIsAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest("CREATE INDEX idx_customers_tag ON customers (tag)", null, null),
            QueryExecutionContext.mcp("admin", true, true),
            "mysql"
        );
        assertThat(decision.mutating()).isTrue();
        assertThat(decision.primaryQueryType()).startsWith("CREATE");
    }

    @Test
    void mcpDeveloperCreate_isForbidden() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("CREATE TABLE t_new (id INT PRIMARY KEY)", null, null),
                QueryExecutionContext.mcp("dev", false, true),
                "mysql"
            )
        );
        assertThat(exception.getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_FORBIDDEN);
    }

    @Test
    void mcpAdminDropTable_isBlockedEvenWhenConfirmed() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DROP TABLE temp_rollup", null, null),
                QueryExecutionContext.mcp("admin", true, true),
                "mysql"
            )
        );
        assertThat(exception.getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("DROP and TRUNCATE");
    }

    @Test
    void mcpAdminDropIndex_isBlockedEvenWhenConfirmed() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DROP INDEX idx_bookings_hotel ON bookings", null, null),
                QueryExecutionContext.mcp("admin", true, true),
                "mysql"
            )
        );
        assertThat(exception.getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("DROP and TRUNCATE");
    }

    @Test
    void mcpAdminTruncate_isBlockedEvenWhenConfirmed() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("TRUNCATE TABLE temp_rollup", null, null),
                QueryExecutionContext.mcp("admin", true, true),
                "mysql"
            )
        );
        assertThat(exception.getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("DROP and TRUNCATE");
    }

    @Test
    void mcpAdminExplainDrop_isBlockedEvenWhenConfirmed() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("EXPLAIN DROP TABLE temp_rollup", null, null),
                QueryExecutionContext.mcp("admin", true, true),
                "mysql"
            )
        );
        assertThat(exception.getErrorCode())
            .isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
        assertThat(exception.getMessage()).contains("DROP and TRUNCATE");
    }

    // ── A malformed statement is a syntax error, not a permissions problem ──────────
    //
    // Reported from the field: a user pasted a SELECT that still carried the double
    // quotes it had in source code and was told "Only admins or DBAs can execute DDL or DML from
    // the SQL Editor", which reads as a permissions problem and sent them looking for a
    // role fix. The statement is neither DDL nor DML — it is not valid SQL at all.
    //
    // The cause is two keyword heuristics disagreeing: QueryNormalizer.detectQueryType
    // sanitizes the prefix away and answers SELECT, while the provider's isReadOnlyQuery
    // strips only comments, still sees the leading quote, and answers false. mutating was
    // computed as (!readOnly && type != UNKNOWN), so "SELECT" became a mutation.

    private static final String QUOTED_SELECT =
        "\"select h.id, h.name, h.city, case when h.country = 'India' then 'IN' "
            + "when h.country = 'United States' then 'US' else 'XX' end country_code from hotel h";

    @Test
    void selectPastedWithItsSurroundingQuotes_isReportedAsASyntaxErrorNotAPermissionError() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest(QUOTED_SELECT, 10, 30),
                QueryExecutionContext.editor("analyst", false, false),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.STATEMENT_NOT_PARSEABLE);
        assertThat(exception.getMessage()).doesNotContain("Only admins or DBAs");
        assertThat(exception.getMessage()).contains("could not parse");
    }

    /**
     * An admin gets the same diagnosis rather than a confirmation prompt. Offering to
     * "confirm this DDL/DML" for a statement nothing managed to classify would invite
     * confirming past the guard, and the statement cannot run anyway.
     */
    @Test
    void aMalformedSelectIsNotOfferedToAdminsAsAConfirmableMutation() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest(QUOTED_SELECT, 10, 30),
                QueryExecutionContext.editor("admin", true, false),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.STATEMENT_NOT_PARSEABLE);
        assertThat(exception.isRequiresConfirmation()).isFalse();
    }

    /** Confirmation cannot get a malformed statement through either. */
    @Test
    void aConfirmedAdminStillCannotRunAMalformedStatement() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest(QUOTED_SELECT, 10, 30),
                QueryExecutionContext.editor("admin", true, true),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.STATEMENT_NOT_PARSEABLE);
    }

    /**
     * The reclassification is gated on the detected verb being read-only, so a write the
     * parser rejects keeps its mutation handling instead of being excused as a typo. This
     * is the half that stops the fix from becoming a bypass.
     */
    @Test
    void anUnparseableWriteIsStillTreatedAsAMutation() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("DELETE FROM hotel WHERE (((", 10, 30),
                QueryExecutionContext.editor("analyst", false, false),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_FORBIDDEN);
    }

    /**
     * The data-modifying CTE this whole guard exists for must not slip through the new
     * branch: `detectHiddenWrite` vetoes it before the parse result is consulted, so a
     * malformed variant is still a blocked write rather than a reported typo.
     */
    @Test
    void aMalformedDataModifyingCteIsStillBlockedAsAWrite() {
        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> service.enforce(
                new QueryRequest("WITH x AS (DELETE FROM hotel RETURNING *) SELECT * FROM x WHERE (((", 10, 30),
                QueryExecutionContext.editor("analyst", false, false),
                "mysql"
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.EDITOR_MUTATION_FORBIDDEN);
    }

    /** The same query without the stray quote is an ordinary read. */
    @Test
    void theSameSelectWithoutTheStrayQuoteIsAllowed() {
        QueryExecutionPolicyService.PolicyDecision decision = service.enforce(
            new QueryRequest(QUOTED_SELECT.substring(1), 10, 30),
            QueryExecutionContext.editor("analyst", false, false),
            "mysql"
        );

        assertThat(decision.mutating()).isFalse();
        assertThat(decision.primaryQueryType()).isEqualTo("SELECT");
    }
}
