package com.dbaagent.service;

import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.QueryExecutionProvider;
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
    @Mock private QueryExecutionProvider queryExecutionProvider;

    private QueryExecutionPolicyService service;

    @BeforeEach
    void setUp() {
        when(providerRegistry.getDialect(anyString())).thenReturn(databaseDialect);
        when(databaseDialect.queryExecution()).thenReturn(queryExecutionProvider);
        lenient().when(providerRegistry.getCanonicalName(anyString())).thenReturn("mysql");
        lenient().when(queryExecutionProvider.isReadOnlyQuery(anyString())).thenReturn(false);
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
}
