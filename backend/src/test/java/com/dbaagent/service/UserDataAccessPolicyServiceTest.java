package com.dbaagent.service;

import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDataAccessPolicyServiceTest {

    @Mock private ConnectionChatAccessPolicyService policyService;
    @Mock private SecurityEventService securityEventService;

    private UserDataAccessPolicyService service;

    @BeforeEach
    void setUp() {
        service = new UserDataAccessPolicyService(policyService, securityEventService);
        lenient().when(policyService.buildProtectionDescriptors(any(ConnectionChatAccessPolicyService.EffectivePolicy.class)))
            .thenAnswer(invocation -> {
                ConnectionChatAccessPolicyService.EffectivePolicy policy = invocation.getArgument(0);
                return Map.of(
                    "customer_profiles",
                    descriptor(null, "customer_profiles", false, "email", "phone_number")
                );
            });
    }

    @Test
    void evaluatePrompt_blocksDirectSensitiveRequestButAllowsAggregate() {
        ConnectionChatAccessPolicyService.EffectivePolicy policy = policy();
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy);

        UserDataAccessPolicyService.PromptDecision blocked = service.evaluatePrompt(
            "conn-1",
            "analyst",
            false,
            "Show me customer emails and phone numbers for active customers"
        );
        UserDataAccessPolicyService.PromptDecision allowed = service.evaluatePrompt(
            "conn-1",
            "analyst",
            false,
            "How many active customers do we have by month?"
        );

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.responseMessage()).contains("protected data");
        assertThat(allowed.allowed()).isTrue();
    }

    @Test
    void enforcePreExecution_blocksRawProtectedColumns() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());

        UserDataAccessPolicyException exception = assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.enforcePreExecution(
                "conn-1",
                new QueryRequest("SELECT email, phone_number FROM customer_profiles", null, null),
                new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo("POLICY_SQL_BLOCKED");
    }

    @Test
    void redactResult_masksProtectedColumnsButKeepsSafeColumns() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());

        QueryResult redacted = service.redactResult(
            "conn-1",
            new QueryResult(
                List.of("customer_name", "email", "phone_number"),
                List.of(List.of("Hotel One", "a@example.com", "1234567890")),
                1,
                1L,
                false,
                12L,
                "SELECT customer_name, email, phone_number FROM customer_profiles"
            ),
            new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
        );

        assertThat(redacted.getRows()).containsExactly(List.of("Hotel One", "[redacted:13]", "[redacted:10]"));
    }

    @Test
    void decorateQuestionWithPolicy_injectsBoundariesForPlanner() {
        String decorated = service.decorateQuestionWithPolicy(policy(), "Show me customer segments by month");

        assertThat(decorated).contains("DeepSQL access policy for this user");
        assertThat(decorated).contains("customer segments by month");
    }

    @Test
    void enforcePreExecution_blocksQueriesOutsideAllowedSchema() {
        ConnectionChatAccessPolicyService.EffectivePolicy schemaPolicy = new ConnectionChatAccessPolicyService.EffectivePolicy(
            true,
            "conn-1",
            "analyst",
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of("marts"),
            true,
            true,
            "Only schema marts",
            List.of(),
            List.of()
        );
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(schemaPolicy);
        when(policyService.buildProtectionDescriptors(schemaPolicy)).thenReturn(Map.of());

        UserDataAccessPolicyException exception = assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.enforcePreExecution(
                "conn-1",
                new QueryRequest("SELECT name FROM crm.customers", null, null),
                new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo("POLICY_SCHEMA_BLOCKED");
    }

    private ConnectionChatAccessPolicyService.EffectivePolicy martsOnlyPolicy() {
        return new ConnectionChatAccessPolicyService.EffectivePolicy(
            true, "conn-1", "analyst",
            Set.of(), Set.of(), Set.of(), Set.of("marts"),
            true, true, "Only schema marts", List.of(), List.of()
        );
    }

    private UserDataAccessPolicyException assertSchemaScopeBlocks(String sql) {
        ConnectionChatAccessPolicyService.EffectivePolicy schemaPolicy = martsOnlyPolicy();
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(schemaPolicy);
        lenient().when(policyService.buildProtectionDescriptors(schemaPolicy)).thenReturn(Map.of());
        return assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.enforcePreExecution(
                "conn-1",
                new QueryRequest(sql, null, null),
                new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
            )
        );
    }

    // The schema allowlist walked only FROM and JOIN, so a forbidden schema
    // reached through any other syntax position was never enumerated and the
    // allowlist silently permitted it.

    // Column inspection cannot reach a select nested inside FROM/JOIN/WHERE, so a
    // protected table referenced there must be refused rather than implicitly allowed.

    @Test
    void enforcePreExecution_blocksProtectedTableInsideDerivedTable() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());

        UserDataAccessPolicyException exception = assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.enforcePreExecution(
                "conn-1",
                new QueryRequest("SELECT t.email FROM (SELECT email FROM customer_profiles) t", null, null),
                new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo("POLICY_SQL_BLOCKED");
    }

    @Test
    void enforcePreExecution_blocksProtectedTableInsideWhereSubquery() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());

        UserDataAccessPolicyException exception = assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.enforcePreExecution(
                "conn-1",
                new QueryRequest("SELECT id FROM orders WHERE id IN (SELECT email FROM customer_profiles)", null, null),
                new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo("POLICY_SQL_BLOCKED");
    }

    // A qualified protection names exactly one table. marts.customer_profiles is a
    // different table from public.customer_profiles and must not be caught by it.
    @Test
    void enforcePreExecution_allowsSameNamedTableInAnotherSchemaWhenProtectionIsQualified() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());
        when(policyService.buildProtectionDescriptors(any(ConnectionChatAccessPolicyService.EffectivePolicy.class)))
            .thenReturn(Map.of("public.customer_profiles",
                descriptor("public", "customer_profiles", false, "email")));

        service.enforcePreExecution(
            "conn-1",
            new QueryRequest("SELECT id FROM (SELECT id FROM marts.customer_profiles) t", null, null),
            new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
        );
    }

    // The genuinely ambiguous direction is an unqualified REFERENCE, not an
    // unqualified protection: qualifyTable() stores public.<t> as bare <t>, so a
    // bare protected name means public, while a bare reference in a query
    // resolves through search_path and could be any schema. Block that one.
    @Test
    void enforcePreExecution_blocksUnqualifiedReferenceBecauseSearchPathIsUnknown() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());

        UserDataAccessPolicyException exception = assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.enforcePreExecution(
                "conn-1",
                new QueryRequest("SELECT id FROM (SELECT id FROM customer_profiles) t", null, null),
                new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo("POLICY_SQL_BLOCKED");
    }

    @Test
    void enforcePreExecution_blocksForbiddenSchemaInsideWhereSubquery() {
        assertThat(assertSchemaScopeBlocks(
            "SELECT id FROM marts.orders WHERE total = (SELECT MAX(salary) FROM hr.salaries)"
        ).getErrorCode()).isEqualTo("POLICY_SCHEMA_BLOCKED");
    }

    @Test
    void enforcePreExecution_blocksForbiddenSchemaInUnionBranch() {
        assertThat(assertSchemaScopeBlocks(
            "SELECT id FROM marts.orders UNION ALL SELECT ssn FROM hr.salaries"
        ).getErrorCode()).isEqualTo("POLICY_SCHEMA_BLOCKED");
    }

    // The same fail-open gate skipped protected-column inspection entirely, so a
    // UNION reached restricted columns even inside an allowed schema.
    @Test
    void enforcePreExecution_blocksProtectedColumnsInUnionBranch() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());

        UserDataAccessPolicyException exception = assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.enforcePreExecution(
                "conn-1",
                new QueryRequest("SELECT 1 AS x UNION ALL SELECT email FROM customer_profiles", null, null),
                new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo("POLICY_SQL_BLOCKED");
    }

    @Test
    void enforcePreExecution_blocksForbiddenSchemaInsideCte() {
        assertThat(assertSchemaScopeBlocks(
            "WITH leaked AS (SELECT ssn FROM hr.salaries) SELECT * FROM leaked"
        ).getErrorCode()).isEqualTo("POLICY_SCHEMA_BLOCKED");
    }

    @Test
    void enforcePreExecution_allowsMartsQueriesWhenOtherSchemasHaveProtectedColumns() {
        ConnectionChatAccessPolicyService.EffectivePolicy schemaPolicy = new ConnectionChatAccessPolicyService.EffectivePolicy(
            true,
            "conn-1",
            "analyst",
            Set.of(),
            Set.of(),
            Set.of("marts.fct_enrollment.amount"),
            Set.of("marts"),
            true,
            true,
            "Only marts; redact amount",
            List.of(),
            List.of("marts.fct_enrollment.amount")
        );
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(schemaPolicy);
        when(policyService.buildProtectionDescriptors(schemaPolicy)).thenReturn(Map.of(
            "marts.fct_enrollment",
            descriptor("marts", "fct_enrollment", false, "amount")
        ));

        assertThat(service.enforcePreExecution(
            "conn-1",
            new QueryRequest("SELECT currency FROM marts.fct_enrollment", null, null),
            new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
        ).policy().allowedSchemas()).containsExactly("marts");
    }

    @Test
    void filterDatabaseObjects_keepsOnlyAllowedSchemas() {
        ConnectionChatAccessPolicyService.EffectivePolicy schemaPolicy = new ConnectionChatAccessPolicyService.EffectivePolicy(
            true,
            "conn-1",
            "analyst",
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of("marts"),
            true,
            true,
            "Only schema marts",
            List.of(),
            List.of()
        );
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(schemaPolicy);

        var marts = new com.dbaagent.model.DatabaseObject("fct_enrollment", "table", "marts", List.of(), 2L, null);
        var crm = new com.dbaagent.model.DatabaseObject("customers", "table", "crm", List.of(), 2L, null);
        var sales = new com.dbaagent.model.DatabaseObject("orders", "table", "sales", List.of(), 2L, null);

        assertThat(service.filterDatabaseObjects("conn-1", "analyst", false, List.of(marts, crm, sales)))
            .extracting(com.dbaagent.model.DatabaseObject::getName)
            .containsExactly("fct_enrollment");
    }

    @Test
    void filterSchemaMetadata_dropsOutOfScopeTablesAndRelationships() {
        ConnectionChatAccessPolicyService.EffectivePolicy schemaPolicy = new ConnectionChatAccessPolicyService.EffectivePolicy(
            true,
            "conn-1",
            "analyst",
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of("marts"),
            true,
            true,
            "Only schema marts",
            List.of(),
            List.of()
        );
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(schemaPolicy);

        var schema = new com.dbaagent.model.SchemaMetadata();
        var marts = new com.dbaagent.model.TableMetadata();
        marts.setSchema("marts");
        marts.setName("fct_enrollment");
        var crm = new com.dbaagent.model.TableMetadata();
        crm.setSchema("crm");
        crm.setName("customers");
        schema.setTables(List.of(marts, crm));
        var relationship = new com.dbaagent.model.RelationshipMetadata();
        relationship.setFromTable("crm.customers");
        relationship.setToTable("marts.fct_enrollment");
        schema.setRelationships(List.of(relationship));

        var filtered = service.filterSchemaMetadata("conn-1", "analyst", false, schema);
        assertThat(filtered.getTables()).extracting(com.dbaagent.model.TableMetadata::getName)
            .containsExactly("fct_enrollment");
        assertThat(filtered.getRelationships()).isEmpty();
    }

    @Test
    void assertTableSchemaAllowed_blocksOutOfScopeTableMetadata() {
        ConnectionChatAccessPolicyService.EffectivePolicy schemaPolicy = new ConnectionChatAccessPolicyService.EffectivePolicy(
            true,
            "conn-1",
            "analyst",
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of("marts"),
            true,
            true,
            "Only schema marts",
            List.of(),
            List.of()
        );
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(schemaPolicy);

        UserDataAccessPolicyException exception = assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.assertTableSchemaAllowed("conn-1", "analyst", false, "crm.customers")
        );
        assertThat(exception.getErrorCode()).isEqualTo("POLICY_SCHEMA_BLOCKED");
        service.assertTableSchemaAllowed("conn-1", "analyst", false, "marts.fct_enrollment");
    }

    private ConnectionChatAccessPolicyService.EffectivePolicy policy() {
        return new ConnectionChatAccessPolicyService.EffectivePolicy(
            true,
            "conn-1",
            "analyst",
            Set.of("PII_MEDIUM"),
            Set.of(),
            Set.of("customer_profiles.email", "customer_profiles.phone_number"),
            Set.of(),
            true,
            true,
            "No PII",
            List.of(),
            List.of("customer_profiles.email", "customer_profiles.phone_number")
        );
    }

    private ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor(
        String schemaName,
        String tableName,
        boolean protectWholeTable,
        String... columns
    ) {
        try {
            var constructor = ConnectionChatAccessPolicyService.ProtectionDescriptor.class
                .getDeclaredConstructor(String.class, String.class, boolean.class, Set.class);
            constructor.setAccessible(true);
            return constructor.newInstance(schemaName, tableName, protectWholeTable, new java.util.LinkedHashSet<>(List.of(columns)));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
