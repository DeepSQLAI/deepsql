package com.dbaagent.service.agent;

import com.dbaagent.service.ResolvedConversationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerVerificationServiceTest {

    private final AnswerVerificationService service = new AnswerVerificationService();

    @Test
    void verify_rejectsClassificationEvidenceForIndexRecommendationPrompt() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.RECOMMEND,
            Set.of(PromptIntent.SubjectType.INDEX, PromptIntent.SubjectType.COLUMN),
            PromptIntent.RequestedOutput.RECOMMENDATION,
            Map.of(),
            false,
            true,
            true,
            false
        );
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.SCHEMA,
            "classification_ranking",
            EvidenceBundle.Source.TABLE_CLASSIFICATION,
            "classification_ranking",
            List.of(Map.of("table", "ACCOUNTS", "role", "FACT")),
            Map.of("role", "FACT"),
            0.85,
            0.9,
            "cached_metadata",
            null,
            Set.of("ACCOUNTS")
        );

        VerificationReport report = service.verify(promptIntent, evidence, ResolvedConversationContext.empty());

        assertFalse(report.passed());
        assertFalse(report.verifiedInsufficiency());
        assertEquals(VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR, report.recommendedFallback());
        assertTrue(report.failureReason().contains("Indexing questions"));
    }

    @Test
    void verify_acceptsVerifiedInsufficiencyForIndexPrompt() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.RECOMMEND,
            Set.of(PromptIntent.SubjectType.INDEX),
            PromptIntent.RequestedOutput.RECOMMENDATION,
            Map.of(),
            false,
            true,
            true,
            false
        );
        EvidenceBundle evidence = EvidenceBundle.insufficient(
            PromptIntent.Domain.PERFORMANCE,
            "insufficiency",
            EvidenceBundle.Source.LIVE_METADATA,
            "insufficiency",
            Map.of("reason", "No stored index recommendations exist yet"),
            0.7,
            0.8,
            "mixed",
            Set.of(),
            "No stored index recommendations exist yet"
        );

        VerificationReport report = service.verify(promptIntent, evidence, ResolvedConversationContext.empty());

        assertTrue(report.passed());
        assertTrue(report.verifiedInsufficiency());
    }

    @Test
    void verify_rejectsSlowQueryEvidenceForTuningPrompt() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.LOOKUP,
            Set.of(PromptIntent.SubjectType.TUNING, PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.PERFORMANCE,
            "slow_query_detail",
            EvidenceBundle.Source.SLOW_QUERY,
            "slow_query_detail",
            List.of(Map.of("query", "select * from bookings", "executionTimeMs", 123250)),
            Map.of("createdAt", "2026-03-16T06:02:55.552514"),
            0.85,
            0.9,
            "cached_metadata",
            null,
            Set.of("BOOKINGS")
        );

        VerificationReport report = service.verify(promptIntent, evidence, ResolvedConversationContext.empty());

        assertFalse(report.passed());
        assertTrue(report.failureReason().contains("Configuration tuning questions"));
    }

    @Test
    void verify_rejectsPairScopedMetadataWhenEvidenceMissesRequestedTable() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.RELATIONSHIP),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            true
        );
        MetadataRequestScope requestScope = new MetadataRequestScope(
            MetadataRequestScope.Mode.ANALYTIC_METADATA,
            MetadataRequestScope.FactType.RELATIONSHIPS,
            List.of("ORDERS", "ORDER_DETAIL"),
            List.of(),
            true,
            true,
            "How are ORDERS and ORDER_DETAIL related?"
        );
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.SCHEMA,
            "relationships",
            EvidenceBundle.Source.INFERRED_RELATIONSHIP,
            "relationships",
            List.<Map<String, Object>>of(Map.of("source", "ORDERS.ID", "target", "CUSTOM_ORDER_INVOICE_NUMBERS.ORDER_ID")),
            Map.of(
                "matchedTables", List.of("ORDERS", "CUSTOM_ORDER_INVOICE_NUMBERS"),
                "scopeSatisfied", false,
                "scopeGapReason", "The metadata evidence did not cover the requested table pair."
            ),
            0.85,
            0.9,
            "cached_metadata",
            null,
            Set.of("ORDERS", "CUSTOM_ORDER_INVOICE_NUMBERS")
        );

        VerificationReport report = service.verify(promptIntent, evidence, ResolvedConversationContext.empty(), requestScope);

        assertFalse(report.passed());
        assertEquals(VerificationReport.RecommendedFallback.LIVE_METADATA, report.recommendedFallback());
        assertTrue(report.failureReason().contains("requested table pair"));
    }

    @Test
    void verify_rejectsSingleTableMetadataWhenEvidenceDriftsToDifferentTable() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.COLUMN),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            false
        );
        MetadataRequestScope requestScope = new MetadataRequestScope(
            MetadataRequestScope.Mode.STRICT_FACT,
            MetadataRequestScope.FactType.TABLE_COLUMNS,
            List.of("CUSTOMER_ORDERS"),
            List.of(),
            false,
            true,
            "What columns are there in CUSTOMER_ORDERS table?"
        );
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.SCHEMA,
            "table_columns",
            EvidenceBundle.Source.SCHEMA_SNAPSHOT,
            "table_columns",
            List.<Map<String, Object>>of(Map.of("column", "name")),
            Map.of(
                "matchedTables", List.of("USER"),
                "scopeSatisfied", false,
                "scopeGapReason", "The metadata evidence drifted away from the requested table."
            ),
            0.8,
            0.9,
            "schema_snapshot",
            null,
            Set.of("USER")
        );

        VerificationReport report = service.verify(promptIntent, evidence, ResolvedConversationContext.empty(), requestScope);

        assertFalse(report.passed());
        assertTrue(report.failureReason().contains("requested table"));
    }

    @Test
    void verify_acceptsDocumentationEvidenceForSchemaRelationshipPrompt() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.RELATIONSHIP),
            PromptIntent.RequestedOutput.EXPLANATION,
            Map.of(),
            false,
            false,
            true,
            true
        );
        MetadataRequestScope requestScope = new MetadataRequestScope(
            MetadataRequestScope.Mode.ANALYTIC_METADATA,
            MetadataRequestScope.FactType.RELATIONSHIPS,
            List.of("ORDERS", "ORDER_DETAIL"),
            List.of(),
            true,
            true,
            "How are ORDERS and ORDER_DETAIL related?",
            MetadataRequestScope.AnswerStyle.EXPLANATORY
        );
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.SCHEMA,
            "relationship_docs",
            EvidenceBundle.Source.COMPANY_KNOWLEDGE,
            "relationship_explanation",
            List.<Map<String, Object>>of(Map.of("summary", "ORDER_DETAIL is the child line-item table for ORDERS.")),
            Map.of(
                "matchedTables", List.of("ORDERS", "ORDER_DETAIL"),
                "scopeSatisfied", true,
                "documentationBacked", true
            ),
            0.84,
            0.9,
            "vault_retrieval",
            null,
            Set.of("ORDERS", "ORDER_DETAIL")
        );

        VerificationReport report = service.verify(promptIntent, evidence, ResolvedConversationContext.empty(), requestScope);

        assertTrue(report.passed());
        assertFalse(report.verifiedInsufficiency());
    }
}
