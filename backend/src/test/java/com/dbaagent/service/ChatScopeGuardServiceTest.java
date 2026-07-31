package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatScopeGuardServiceTest {

    private final ChatScopeGuardService service = new ChatScopeGuardService();

    @Test
    void allowsRecognizedBiQueries() {
        ChatScopeGuardService.ScopeDecision decision = service.evaluate(
            "Show revenue by customer for last week",
            "Show revenue by customer for last week",
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BI_QUERY,
                ChatQuestionRoutingService.BrainTopic.GENERAL
            )
        );

        assertTrue(decision.allowed());
    }

    @Test
    void blocksExplicitWebRequests() {
        ChatScopeGuardService.ScopeDecision decision = service.evaluate(
            "Search the web for the latest PostgreSQL release notes",
            "Search the web for the latest PostgreSQL release notes",
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.GENERAL,
                ChatQuestionRoutingService.BrainTopic.GENERAL
            )
        );

        assertFalse(decision.allowed());
        assertEquals("external_context_blocked", decision.reasonCode());
    }

    @Test
    void allowsShortClarificationFollowUps() {
        ChatScopeGuardService.ScopeDecision decision = service.evaluate(
            "yes use p.created_at",
            "yes use p.created_at",
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.GENERAL,
                ChatQuestionRoutingService.BrainTopic.GENERAL
            )
        );

        assertTrue(decision.allowed());
    }

    @Test
    void allowsFollowUpsWhenEffectiveQuestionCarriesCompanyContext() {
        ChatScopeGuardService.ScopeDecision decision = service.evaluate(
            "What about last week?",
            "Compare bookings by property for last week using the same connection context",
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BI_QUERY,
                ChatQuestionRoutingService.BrainTopic.GENERAL
            )
        );

        assertTrue(decision.allowed());
    }
}
