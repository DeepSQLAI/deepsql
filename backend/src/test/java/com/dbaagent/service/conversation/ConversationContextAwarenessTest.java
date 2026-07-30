package com.dbaagent.service.conversation;

import com.dbaagent.model.ChatTurnContext;
import com.dbaagent.repository.ChatMessageRepository;
import com.dbaagent.repository.ChatTurnContextRepository;
import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.ConversationContextService;
import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.service.agent.AgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationContextAwarenessTest {

    @Mock private ChatTurnContextRepository chatTurnContextRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private AgentRunService agentRunService;

    private ConversationContextService service;

    @BeforeEach
    void setUp() {
        service = new ConversationContextService(
            chatTurnContextRepository,
            chatMessageRepository,
            agentRunService,
            new ChatQuestionRoutingService(),
            new ObjectMapper()
        );
    }

    @Test
    void resolveRelatedContext_keepsFollowUpOnOriginalTopicAcrossUnrelatedTurn() {
        ChatTurnContext onboardingChain = context(
            "ctx-onboarding",
            null,
            "chat-1",
            "show me all the properties that are onboarded in the last 3 days and are active",
            "show me all the properties that are onboarded in the last 3 days and are active",
            "Active onboarded hotel properties from HOTEL for the last 3 days.",
            """
                {"tables":["hotel"],"entities":["property"],"filters":["active"],"timeframe":"last 3 days"}
                """,
            """
                [{"displayLabel":"Hotel Alpha","entityType":"property","table":"hotel"},{"displayLabel":"Hotel Beta","entityType":"property","table":"hotel"}]
                """,
            "hotel property onboarded active last 3 days selected properties",
            "BI_QUERY",
            "RESOLVED"
        );

        ChatTurnContext unrelatedSchemaQuestion = context(
            "ctx-schema",
            null,
            "chat-1",
            "how many tables are in the schema",
            "how many tables are in the schema",
            "Schema metadata table count.",
            """
                {"metric":"table count","tables":["information_schema"]}
                """,
            "[]",
            "schema metadata tables count",
            "BRAIN_METADATA",
            "RESOLVED"
        );

        when(chatTurnContextRepository.findByChatIdOrderByCreatedAtDesc("chat-1"))
            .thenReturn(List.of(unrelatedSchemaQuestion, onboardingChain));

        ResolvedConversationContext resolved = service.resolveRelatedContext(
            "conn-1",
            "chat-1",
            "what is the booking volume for these properties?"
        );

        assertTrue(resolved.hasMatchedContext());
        assertEquals("ctx-onboarding", resolved.matchedContextId());
        assertEquals("show me all the properties that are onboarded in the last 3 days and are active", resolved.anchorQuestion());
        assertEquals("last 3 days", resolved.resolvedContext().get("timeframe"));
        assertFalse(resolved.selectedEntities().isEmpty());
    }

    @Test
    void resolveRelatedContext_prefers_clarification_chain_over_failed_recent_turn() {
        ChatTurnContext clarificationChain = context(
            "ctx-clarify",
            null,
            "chat-2",
            "how many hotels are onboarded in the last 3 days?",
            "how many hotels are onboarded in the last 3 days?",
            "Need one clarification: use subscription_start_date for onboarding.",
            """
                {"tables":["hotel"],"entities":["hotel"],"timeframe":"last 3 days","chosenTemporalColumn":"subscription_start_date"}
                """,
            "[]",
            "hotel onboarding last 3 days subscription_start_date clarification",
            "BI_QUERY",
            "CLARIFICATION"
        );

        ChatTurnContext failedTurn = context(
            "ctx-failed",
            null,
            "chat-2",
            "user bookings table",
            "user bookings table",
            "I encountered an error executing the query.",
            """
                {"tables":["user_bookings"]}
                """,
            "[]",
            "failed booking query",
            "BI_QUERY",
            "FAILED"
        );

        when(chatTurnContextRepository.findByChatIdOrderByCreatedAtDesc("chat-2"))
            .thenReturn(List.of(failedTurn, clarificationChain));

        ResolvedConversationContext resolved = service.resolveRelatedContext(
            "conn-1",
            "chat-2",
            "use that onboarding date and only active ones"
        );

        assertTrue(resolved.hasMatchedContext());
        assertEquals("ctx-clarify", resolved.matchedContextId());
        assertEquals("subscription_start_date", resolved.resolvedContext().get("chosenTemporalColumn"));
    }

    @Test
    void resolveRelatedContext_returnsEmptyForFreshUnrelatedTopicWithoutCue() {
        ChatTurnContext bookingsChain = context(
            "ctx-bookings",
            null,
            "chat-3",
            "what is the booking volume for these properties?",
            "what is the booking volume for these properties?",
            "Booking volume for the selected hotels.",
            """
                {"tables":["user_bookings"],"entities":["booking"],"metric":"booking volume"}
                """,
            """
                [{"displayLabel":"Hotel Alpha","entityType":"property","table":"hotel"}]
                """,
            "booking volume selected properties",
            "BI_QUERY",
            "RESOLVED"
        );

        when(chatTurnContextRepository.findByChatIdOrderByCreatedAtDesc("chat-3"))
            .thenReturn(List.of(bookingsChain));

        ResolvedConversationContext resolved = service.resolveRelatedContext(
            "conn-1",
            "chat-3",
            "what are the largest fact tables?"
        );

        assertFalse(resolved.hasMatchedContext());
        assertTrue(resolved.conversationHistory().isEmpty());
    }

    @Test
    void buildEffectiveQuestion_uses_compacted_scope_not_raw_transcript_dump() {
        ResolvedConversationContext context = new ResolvedConversationContext(
            "ctx-1",
            "BI_QUERY",
            "RESOLVED",
            "show me all the properties that are onboarded in the last 3 days and are active",
            "Active onboarded properties selected from HOTEL using subscription_start_date.",
            java.util.Map.of(
                "tables", List.of("hotel"),
                "filters", List.of("active"),
                "timeframe", "last 3 days",
                "chosenTemporalColumn", "subscription_start_date"
            ),
            List.of(
                java.util.Map.of("displayLabel", "Hotel Alpha", "table", "hotel"),
                java.util.Map.of("displayLabel", "Hotel Beta", "table", "hotel")
            ),
            java.util.Map.of("rowCount", 2),
            "SELECT id, property_name FROM hotel WHERE active = 1",
            List.of(),
            0.77d
        );

        String effectiveQuestion = service.buildEffectiveQuestion(
            "what is the booking volume for these properties?",
            context
        );

        assertTrue(effectiveQuestion.contains("Resolved related conversation context"));
        assertTrue(effectiveQuestion.contains("Active onboarded properties selected from HOTEL"));
        assertTrue(effectiveQuestion.contains("Hotel Alpha"));
        assertTrue(effectiveQuestion.contains("subscription_start_date"));
        assertTrue(effectiveQuestion.contains("Current user request: what is the booking volume for these properties?"));
    }

    private ChatTurnContext context(
        String id,
        String parentId,
        String chatId,
        String anchorQuestion,
        String currentQuestion,
        String chainSummary,
        String resolvedContextJson,
        String selectedEntitiesJson,
        String topicSignature,
        String routeType,
        String stateStatus
    ) {
        ChatTurnContext context = new ChatTurnContext();
        context.setId(id);
        context.setParentContextId(parentId);
        context.setChatId(chatId);
        context.setConnectionId("conn-1");
        context.setUserMessageId("user-" + id);
        context.setAssistantMessageId("assistant-" + id);
        context.setAnchorQuestion(anchorQuestion);
        context.setCurrentQuestion(currentQuestion);
        context.setQuestionSummary(currentQuestion);
        context.setAnswerSummary(chainSummary);
        context.setChainSummary(chainSummary);
        context.setResolvedContextJson(resolvedContextJson);
        context.setSelectedEntitiesJson(selectedEntitiesJson);
        context.setTopicSignature(topicSignature);
        context.setRouteType(routeType);
        context.setStateStatus(stateStatus);
        return context;
    }
}
