package com.dbaagent.service.agent;

import com.dbaagent.service.ChatQuestionRoutingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPromptClassifierTest {

    private final AgentPromptClassifier classifier = new AgentPromptClassifier();
    private final ChatQuestionRoutingService routingService = new ChatQuestionRoutingService();

    @Test
    void classify_subscriptionRevenuePrompt_staysUniversal() {
        AgentDecision decision = classifier.classify(
            "What is the subscription revenue collected this month in INR and USD?",
            routingService.classify("What is the subscription revenue collected this month in INR and USD?")
        );

        assertTrue(decision.useAgenticFlow());
        assertEquals(AgentIntent.UNIVERSAL_CHAT, decision.intent());
    }

    @Test
    void classify_churnPrompt_staysUniversal() {
        AgentDecision decision = classifier.classify(
            "Which customers are about to churn because usage steeply dropped recently?",
            routingService.classify("Which customers are about to churn because usage steeply dropped recently?")
        );

        assertTrue(decision.useAgenticFlow());
        assertEquals(AgentIntent.UNIVERSAL_CHAT, decision.intent());
    }

    @Test
    void classify_accountsModulePrompt_staysUniversal() {
        AgentDecision decision = classifier.classify(
            "Which tables should I use to build a comprehensive accounts module and customer ledgers?",
            routingService.classify("Which tables should I use to build a comprehensive accounts module and customer ledgers?")
        );

        assertTrue(decision.useAgenticFlow());
        assertEquals(AgentIntent.UNIVERSAL_CHAT, decision.intent());
    }

    @Test
    void classify_simpleMetadataPrompt_usesMetadataAgenticFlow() {
        AgentDecision decision = classifier.classify(
            "How many key columns do we have?",
            routingService.classify("How many key columns do we have?")
        );

        assertTrue(decision.useAgenticFlow());
        assertEquals(AgentIntent.METADATA_ANALYSIS, decision.intent());
    }

    @Test
    void classify_genericBiPrompt_usesUniversalAgentIntent() {
        AgentDecision decision = classifier.classify(
            "give me top 5 customers by bookings volume in the last 3 days",
            routingService.classify("give me top 5 customers by bookings volume in the last 3 days")
        );

        assertTrue(decision.useAgenticFlow());
        assertEquals(AgentIntent.UNIVERSAL_CHAT, decision.intent());
    }
}
