package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.brain.BrainRule;
import com.dbaagent.repository.brain.BrainRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessRuleMemoryServiceTest {

    @Mock
    private BrainRuleRepository brainRuleRepository;

    private BusinessRuleMemoryService service;

    @BeforeEach
    void setUp() {
        service = new BusinessRuleMemoryService(brainRuleRepository);
    }

    @Test
    void learnFromFeedbackExtractsJoinAndPredicateGuardrails() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            new TableMetadata("CUSTOMERS", "public", "table", null, null, List.of(), List.of()),
            new TableMetadata("PRODUCT_PRICING", "public", "table", null, null, List.of(), List.of()),
            new TableMetadata("ACCOUNTS", "public", "table", null, null, List.of(), List.of()),
            new TableMetadata("ACCOUNTS_LEDGER", "public", "table", null, null, List.of(), List.of())
        ));

        when(brainRuleRepository.findByConnectionIdAndIsActiveTrueOrderByCreatedAtDesc(eq("conn-1")))
            .thenReturn(new ArrayList<>());
        when(brainRuleRepository.save(any(BrainRule.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        String feedback = "It should join ACCOUNTS and ACCOUNTS_LEDGER on group_id and type=CREDIT and mode=SUBSCRIPTION instead of CUSTOMERS and PRODUCT_PRICING";

        int learned = service.learnFromFeedback(
            "conn-1",
            feedback,
            null,
            null,
            "unit-test",
            schema
        );

        assertTrue(learned > 0);

        ArgumentCaptor<BrainRule> captor = ArgumentCaptor.forClass(BrainRule.class);
        verify(brainRuleRepository, atLeast(1)).save(captor.capture());

        List<BrainRule> savedRules = captor.getAllValues();
        assertTrue(savedRules.stream().anyMatch(r ->
            BusinessRuleMemoryService.RULE_REQUIRED_TABLE.equals(r.getRuleType()) &&
                "accounts".equalsIgnoreCase(r.getTableName())
        ));
        assertTrue(savedRules.stream().anyMatch(r ->
            BusinessRuleMemoryService.RULE_REQUIRED_TABLE.equals(r.getRuleType()) &&
                "accounts_ledger".equalsIgnoreCase(r.getTableName())
        ));
        assertTrue(savedRules.stream().anyMatch(r ->
            BusinessRuleMemoryService.RULE_PROHIBITED_TABLE.equals(r.getRuleType()) &&
                "customers".equalsIgnoreCase(r.getTableName())
        ));
        assertTrue(savedRules.stream().anyMatch(r ->
            BusinessRuleMemoryService.RULE_REQUIRED_PREDICATE.equals(r.getRuleType()) &&
                "type".equalsIgnoreCase(r.getColumnName())
        ));
        assertTrue(savedRules.stream().anyMatch(r ->
            BusinessRuleMemoryService.RULE_REQUIRED_PREDICATE.equals(r.getRuleType()) &&
                "mode".equalsIgnoreCase(r.getColumnName())
        ));
    }

    @Test
    void evaluateSqlFlagsViolationsAndAcceptsCorrectedQuery() {
        List<BusinessRuleMemoryService.SqlGuardrail> guardrails = List.of(
            new BusinessRuleMemoryService.SqlGuardrail(
                BusinessRuleMemoryService.GuardrailType.REQUIRED_TABLE,
                "accounts",
                null,
                null,
                "rule",
                90
            ),
            new BusinessRuleMemoryService.SqlGuardrail(
                BusinessRuleMemoryService.GuardrailType.REQUIRED_TABLE,
                "accounts_ledger",
                null,
                null,
                "rule",
                90
            ),
            new BusinessRuleMemoryService.SqlGuardrail(
                BusinessRuleMemoryService.GuardrailType.PROHIBITED_TABLE,
                "customer",
                null,
                null,
                "rule",
                90
            ),
            new BusinessRuleMemoryService.SqlGuardrail(
                BusinessRuleMemoryService.GuardrailType.REQUIRED_JOIN_KEY,
                null,
                "group_id",
                null,
                "rule",
                90
            ),
            new BusinessRuleMemoryService.SqlGuardrail(
                BusinessRuleMemoryService.GuardrailType.REQUIRED_PREDICATE,
                null,
                "type",
                "credit",
                "rule",
                90
            ),
            new BusinessRuleMemoryService.SqlGuardrail(
                BusinessRuleMemoryService.GuardrailType.REQUIRED_PREDICATE,
                null,
                "mode",
                "subscription",
                "rule",
                90
            )
        );

        String incorrectSql = "SELECT SUM(hp.amount) FROM customer h JOIN product_pricing hp ON h.id = hp.customer_id";
        BusinessRuleMemoryService.SqlGuardrailEvaluation incorrectEval = service.evaluateSql(incorrectSql, guardrails);
        assertFalse(incorrectEval.passed());
        assertTrue(incorrectEval.summary().toLowerCase().contains("missing required table 'accounts'"));
        assertTrue(incorrectEval.summary().toLowerCase().contains("prohibited table 'customer'"));

        String correctedSql = "SELECT SUM(al.amount) AS subscription_revenue " +
            "FROM accounts a JOIN accounts_ledger al ON a.group_id = al.group_id " +
            "WHERE al.type = 'CREDIT' AND al.mode = 'SUBSCRIPTION'";
        BusinessRuleMemoryService.SqlGuardrailEvaluation correctedEval = service.evaluateSql(correctedSql, guardrails);
        assertTrue(correctedEval.passed());
    }

    @Test
    void resolveApplicableGuardrailsUsesQuestionTokenMatching() {
        BrainRule subscriptionRule = BrainRule.builder()
            .connectionId("conn-1")
            .ruleType(BusinessRuleMemoryService.RULE_REQUIRED_TABLE)
            .tableName("accounts")
            .ruleText("source: subscription revenue must use accounts")
            .confidence(95)
            .isActive(true)
            .build();

        BrainRule unrelatedRule = BrainRule.builder()
            .connectionId("conn-1")
            .ruleType(BusinessRuleMemoryService.RULE_REQUIRED_TABLE)
            .tableName("inventory_items")
            .ruleText("source: inventory valuation report")
            .confidence(95)
            .isActive(true)
            .build();

        when(brainRuleRepository.findByConnectionIdAndIsActiveTrueOrderByCreatedAtDesc("conn-1"))
            .thenReturn(List.of(subscriptionRule, unrelatedRule));

        List<BusinessRuleMemoryService.SqlGuardrail> applicable = service.resolveApplicableGuardrails(
            "conn-1",
            "how much subscription revenue did we collect in 2025",
            null
        );

        assertTrue(applicable.stream().anyMatch(g -> "accounts".equals(g.tableName())));
        assertFalse(applicable.stream().anyMatch(g -> "inventory_items".equals(g.tableName())));
    }

    @Test
    void learnFromFeedbackSkipsNonSqlTextEvenWithHints() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            new TableMetadata(
                "ACCOUNTS",
                "public",
                "table",
                null,
                null,
                List.of(new ColumnMetadata("status", "varchar", null, true, false, null, 1)),
                List.of()
            )
        ));

        int learned = service.learnFromFeedback(
            "conn-1",
            "for this analysis use the slow query tab",
            "accounts",
            "status",
            "unit-test",
            schema
        );

        assertEquals(0, learned);
        verify(brainRuleRepository, never()).save(any(BrainRule.class));
    }
}
