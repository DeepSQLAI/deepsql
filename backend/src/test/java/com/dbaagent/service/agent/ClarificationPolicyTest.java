package com.dbaagent.service.agent;

import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.service.ConversationCarryoverDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationPolicyTest {

    private final ClarificationPolicy policy = new ClarificationPolicy();

    @Test
    void vagueTrendPrompt_requestsTimeClarification() {
        String clarification = policy.clarificationForUnderspecifiedPrompt(
            "Show booking revenue trend",
            ResolvedConversationContext.empty()
        );

        assertThat(clarification).containsIgnoringCase("time period")
            .containsIgnoringCase("time")
            .containsIgnoringCase("date range")
            .containsIgnoringCase("period");
    }

    @Test
    void vagueRankingPrompt_requestsMetricClarification() {
        String clarification = policy.clarificationForUnderspecifiedPrompt(
            "Show top customers",
            ResolvedConversationContext.empty()
        );

        assertThat(clarification).containsIgnoringCase("metric")
            .containsIgnoringCase("amount")
            .containsIgnoringCase("revenue")
            .containsIgnoringCase("time period");
    }

    @Test
    void followUpPrompt_reusesPriorMetricAndTimeframeWithoutClarification() {
        ResolvedConversationContext resolvedConversationContext = new ResolvedConversationContext(
            "ctx-1",
            "BI_QUERY",
            "RESOLVED",
            "Show top customers",
            "Top customers by total booking amount in the last 30 days.",
            Map.of(
                "metric", "booking amount",
                "timeframe", "last 30 days"
            ),
            List.of(),
            Map.of(),
            null,
            List.of(),
            0.92
        );

        String clarification = policy.clarificationForUnderspecifiedPrompt(
            "Now only cancelled ones.",
            resolvedConversationContext
        );

        assertThat(clarification).isNull();
    }

    @Test
    void trendPromptWithRelativeWindow_doesNotClarifyForMissingGrain() {
        String clarification = policy.clarificationForUnderspecifiedPrompt(
            "Show booking revenue trend for last 30 days",
            ResolvedConversationContext.empty()
        );

        assertThat(clarification).isNull();
    }

    @Test
    void clarificationAnswerMode_suppressesRedundantClarification() {
        ResolvedConversationContext resolvedConversationContext = new ResolvedConversationContext(
            "ctx-1",
            "BI_QUERY",
            "CLARIFICATION",
            "Show booking revenue trend",
            "Need one clarification about the date range.",
            Map.of(
                "metric", "booking revenue"
            ),
            List.of(),
            Map.of(),
            null,
            List.of(),
            0.92
        );

        String clarification = policy.clarificationForUnderspecifiedPrompt(
            "last 30 days",
            resolvedConversationContext,
            new ConversationCarryoverDecision(
                ConversationCarryoverDecision.ReuseMode.ANSWER_CLARIFICATION,
                "ctx-1",
                "BI_QUERY",
                "CLARIFICATION",
                List.of("CUSTOMER_ORDERS"),
                List.of(),
                null,
                "booking revenue",
                List.of(),
                0.94d,
                "Clarification answer"
            )
        );

        assertThat(clarification).isNull();
    }
}
