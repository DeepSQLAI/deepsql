package com.dbaagent.service.agent;

import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.ResolvedConversationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptIntentAnalyzerTest {

    private final PromptIntentAnalyzer analyzer = new PromptIntentAnalyzer();

    @Test
    void analyze_indexingPrompt_routesToPerformanceRecommend() {
        PromptIntent intent = analyzer.analyze(
            "which columns need immediate indexing?",
            "which columns need immediate indexing?",
            ResolvedConversationContext.empty(),
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.SCHEMA
            )
        );

        assertEquals(PromptIntent.Domain.PERFORMANCE, intent.domain());
        assertEquals(PromptIntent.TaskType.RECOMMEND, intent.taskType());
        assertTrue(intent.subjectTypes().contains(PromptIntent.SubjectType.INDEX));
        assertTrue(intent.subjectTypes().contains(PromptIntent.SubjectType.COLUMN));
        assertEquals(PromptIntent.RequestedOutput.RECOMMENDATION, intent.requestedOutput());
        assertTrue(intent.requiresLiveMetadata());
        assertFalse(intent.requiresSql());
    }

    @Test
    void analyze_biMetricPrompt_routesToBiSqlQuery() {
        PromptIntent intent = analyzer.analyze(
            "How many hotels were onboarded in the last 3 days?",
            "How many hotels were onboarded in the last 3 days?",
            ResolvedConversationContext.empty(),
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BI_QUERY,
                ChatQuestionRoutingService.BrainTopic.GENERAL
            )
        );

        assertEquals(PromptIntent.Domain.BI, intent.domain());
        assertEquals(PromptIntent.TaskType.SQL_QUERY, intent.taskType());
        assertEquals(PromptIntent.RequestedOutput.SQL_RESULT, intent.requestedOutput());
        assertTrue(intent.requiresSql());
    }

    @Test
    void analyze_priorQueryDiagnosticFollowUp_staysOnPerformanceEvenWhenMatchedRouteWasBi() {
        ResolvedConversationContext priorContext = new ResolvedConversationContext(
            "ctx-1",
            ChatQuestionRoutingService.RouteType.BI_QUERY.name(),
            "RESOLVED",
            "what are the top 3 slow queries?",
            "Returned the full SQL for the first slow query and summarized the scan behavior.",
            java.util.Map.of(),
            java.util.List.of(),
            java.util.Map.of(),
            "SELECT * FROM orders",
            java.util.List.of(),
            0.91d
        );

        PromptIntent intent = analyzer.analyze(
            "show me the full query for the first one and explain why it is scanning so many rows",
            "show me the full query for the first one and explain why it is scanning so many rows",
            priorContext,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BI_QUERY,
                ChatQuestionRoutingService.BrainTopic.GENERAL
            )
        );

        assertEquals(PromptIntent.Domain.PERFORMANCE, intent.domain());
        assertEquals(PromptIntent.TaskType.TROUBLESHOOT, intent.taskType());
        assertFalse(intent.requiresSql());
        assertTrue(intent.requiresCachedMetadata());
    }

    @Test
    void analyze_costBenefitPerformanceFixPrompt_routesToPerformanceRecommendation() {
        PromptIntent intent = analyzer.analyze(
            "What is the expected ROI or cost benefit of the top performance fixes?",
            "What is the expected ROI or cost benefit of the top performance fixes?",
            ResolvedConversationContext.empty(),
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.PERFORMANCE
            )
        );

        assertEquals(PromptIntent.Domain.PERFORMANCE, intent.domain());
        assertEquals(PromptIntent.TaskType.RECOMMEND, intent.taskType());
        assertEquals(PromptIntent.RequestedOutput.RANKING, intent.requestedOutput());
        assertTrue(intent.subjectTypes().contains(PromptIntent.SubjectType.QUERY));
        assertFalse(intent.requiresSql());
    }
}
