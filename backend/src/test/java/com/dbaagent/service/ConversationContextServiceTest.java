package com.dbaagent.service;

import com.dbaagent.dto.AgentRunTraceResponse;
import com.dbaagent.model.ChatTurnContext;
import com.dbaagent.model.QueryResult;
import com.dbaagent.repository.ChatMessageRepository;
import com.dbaagent.repository.ChatTurnContextRepository;
import com.dbaagent.service.agent.AgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationContextServiceTest {

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
    void resolveRelatedContext_prefersEarlierMatchingChainOverRecentUnrelatedTurn() {
        ChatTurnContext related = new ChatTurnContext();
        related.setId("ctx-related");
        related.setChatId("chat-1");
        related.setUserMessageId("u-1");
        related.setAssistantMessageId("a-1");
        related.setAnchorQuestion("show me all the properties that are onboarded in the last 3 days and are active");
        related.setCurrentQuestion("show me all the properties that are onboarded in the last 3 days and are active");
        related.setQuestionSummary("onboarded active properties");
        related.setAnswerSummary("Returned a list of active onboarded properties.");
        related.setChainSummary("Active onboarded hotel properties selected from the HOTEL table.");
        related.setResolvedContextJson("""
            {"tables":["hotel"],"entities":["property"],"filters":["active"],"timeframe":"last 3 days"}
            """);
        related.setSelectedEntitiesJson("""
            [{"displayLabel":"Hotel Alpha","entityType":"property","table":"hotel"},{"displayLabel":"Hotel Beta","entityType":"property","table":"hotel"}]
            """);
        related.setTopicSignature("hotel property onboarded active last 3 days selected properties");
        related.setRouteType("BI_QUERY");
        related.setStateStatus("RESOLVED");

        ChatTurnContext unrelated = new ChatTurnContext();
        unrelated.setId("ctx-unrelated");
        unrelated.setChatId("chat-1");
        unrelated.setUserMessageId("u-2");
        unrelated.setAssistantMessageId("a-2");
        unrelated.setAnchorQuestion("how many tables are in the schema");
        unrelated.setCurrentQuestion("how many tables are in the schema");
        unrelated.setQuestionSummary("schema table count");
        unrelated.setAnswerSummary("Returned the table count.");
        unrelated.setChainSummary("Counted schema tables from metadata.");
        unrelated.setResolvedContextJson("""
            {"tables":["information_schema"],"metric":"table count"}
            """);
        unrelated.setTopicSignature("schema metadata tables count");
        unrelated.setRouteType("BRAIN_METADATA");
        unrelated.setStateStatus("RESOLVED");

        when(chatTurnContextRepository.findByChatIdOrderByCreatedAtDesc("chat-1"))
            .thenReturn(List.of(unrelated, related));

        ResolvedConversationContext resolved = service.resolveRelatedContext(
            "conn-1",
            "chat-1",
            "what is the booking volume for these properties?"
        );

        assertTrue(resolved.hasMatchedContext());
        assertEquals("ctx-related", resolved.matchedContextId());
        assertEquals("show me all the properties that are onboarded in the last 3 days and are active", resolved.anchorQuestion());
        assertTrue(resolved.chainSummary().contains("hotel properties"));
        assertEquals("hotel", ((List<?>) resolved.resolvedContext().get("tables")).getFirst());
        assertFalse(resolved.selectedEntities().isEmpty());
    }

    @Test
    void resolveRelatedContext_matchesOrdinalPriorQueryFollowUpToSlowQueryTurn() {
        ChatTurnContext slowQueryTurn = new ChatTurnContext();
        slowQueryTurn.setId("ctx-slow-query");
        slowQueryTurn.setChatId("chat-1");
        slowQueryTurn.setUserMessageId("u-10");
        slowQueryTurn.setAssistantMessageId("a-10");
        slowQueryTurn.setAnchorQuestion("what are the top 3 slow queries? what is causing the slowness?");
        slowQueryTurn.setCurrentQuestion("what are the top 3 slow queries? what is causing the slowness?");
        slowQueryTurn.setQuestionSummary("top slow queries and cause analysis");
        slowQueryTurn.setAnswerSummary("Returned the top slow queries with scan causes.");
        slowQueryTurn.setChainSummary("Ranked slow queries from cached performance history and explained the likely scan bottlenecks.");
        slowQueryTurn.setResolvedContextJson("""
            {"tables":["CM_LOGS_NEW"],"metric":"slow query ranking"}
            """);
        slowQueryTurn.setSourceSql("SELECT * FROM CM_LOGS_NEW WHERE hotel_id = 42 ORDER BY update_time DESC");
        slowQueryTurn.setTopicSignature("slow query ranking full sql scan rows performance");
        slowQueryTurn.setRouteType("BRAIN_METADATA");
        slowQueryTurn.setStateStatus("RESOLVED");

        ChatTurnContext unrelated = new ChatTurnContext();
        unrelated.setId("ctx-unrelated");
        unrelated.setChatId("chat-1");
        unrelated.setUserMessageId("u-11");
        unrelated.setAssistantMessageId("a-11");
        unrelated.setAnchorQuestion("how many tables are in the schema");
        unrelated.setCurrentQuestion("how many tables are in the schema");
        unrelated.setQuestionSummary("schema table count");
        unrelated.setAnswerSummary("Returned the table count.");
        unrelated.setChainSummary("Counted schema tables from metadata.");
        unrelated.setResolvedContextJson("""
            {"tables":["information_schema"],"metric":"table count"}
            """);
        unrelated.setTopicSignature("schema metadata tables count");
        unrelated.setRouteType("BRAIN_METADATA");
        unrelated.setStateStatus("RESOLVED");

        when(chatTurnContextRepository.findByChatIdOrderByCreatedAtDesc("chat-1"))
            .thenReturn(List.of(unrelated, slowQueryTurn));

        ResolvedConversationContext resolved = service.resolveRelatedContext(
            "conn-1",
            "chat-1",
            "show me the full query for the first one and explain why it is scanning so many rows"
        );

        assertTrue(resolved.hasMatchedContext());
        assertEquals("ctx-slow-query", resolved.matchedContextId());
        assertEquals("SELECT * FROM CM_LOGS_NEW WHERE hotel_id = 42 ORDER BY update_time DESC", resolved.sourceSql());
        assertTrue(resolved.chainSummary().toLowerCase().contains("slow queries"));
    }

    @Test
    void recordTurn_preservesParentChainAndSelectedScope() {
        ResolvedConversationContext priorContext = new ResolvedConversationContext(
            "ctx-parent",
            "BI_QUERY",
            "RESOLVED",
            "show me all the properties that are onboarded in the last 3 days and are active",
            "Active onboarded hotel properties selected from HOTEL.",
            Map.of(
                "tables", List.of("hotel"),
                "filters", List.of("active"),
                "timeframe", "last 3 days"
            ),
            List.of(Map.of("displayLabel", "Hotel Alpha", "entityType", "property", "table", "hotel")),
            Map.of("rowCount", 5),
            "SELECT hotel_id, property_name FROM hotel WHERE active = 1",
            List.of(),
            0.74d
        );

        QueryResult queryResult = new QueryResult(
            List.of("hotel_id", "property_name", "booking_count"),
            List.of(List.of(101, "Hotel Alpha", 18)),
            1,
            1L,
            false,
            25L,
            "SELECT hotel_id, property_name, COUNT(*) AS booking_count FROM user_bookings"
        );

        when(chatTurnContextRepository.findByAssistantMessageId("a-3")).thenReturn(Optional.empty());
        when(chatTurnContextRepository.save(any(ChatTurnContext.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatTurnContext saved = service.recordTurn(new ConversationContextService.TurnSnapshotRequest(
            "conn-1",
            "chat-1",
            "u-3",
            "a-3",
            "what is the booking volume for these properties?",
            "Resolved related conversation context ... Current user request: what is the booking volume for these properties?",
            "Hotel Alpha has 18 bookings.",
            "BI_QUERY",
            "UNIVERSAL_CHAT",
            "SELECT hotel_id, property_name, COUNT(*) AS booking_count FROM user_bookings",
            queryResult,
            0.91d,
            null,
            true,
            priorContext,
            "REUSE_SCOPE",
            "thread_context",
            List.of("Reused prior property scope for booking volume analysis."),
            "verified"
        ));

        assertEquals("ctx-parent", saved.getParentContextId());
        assertEquals("show me all the properties that are onboarded in the last 3 days and are active", saved.getAnchorQuestion());
        assertTrue(saved.getSelectedEntitiesJson().contains("Hotel Alpha"));
        assertTrue(saved.getResolvedContextJson().contains("hotel"));
        assertTrue(saved.getSourceSql().contains("user_bookings"));
        assertEquals("RESOLVED", saved.getStateStatus());
    }

    @Test
    void recordTurn_persistsTemporalAndJoinDecisionsFromAgentTrace() {
        when(chatTurnContextRepository.findByAssistantMessageId("a-4")).thenReturn(Optional.empty());
        when(chatTurnContextRepository.save(any(ChatTurnContext.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunTraceResponse trace = new AgentRunTraceResponse();
        AgentRunTraceResponse.StepDto step = new AgentRunTraceResponse.StepDto();
        AgentRunTraceResponse.ObservationDto observation = new AgentRunTraceResponse.ObservationDto();
        observation.setData(Map.of(
            "resolvedTables", List.of("GUEST_MAPPING", "USER_BOOKINGS"),
            "chosenTemporalColumn", "USER_BOOKINGS.booking_made_on",
            "chosenJoinPath", List.of("GUEST_MAPPING.booking_id = USER_BOOKINGS.id")
        ));
        step.setObservation(observation);
        trace.setSteps(List.of(step));
        when(agentRunService.getTrace("run-1")).thenReturn(Optional.of(trace));

        ChatTurnContext saved = service.recordTurn(new ConversationContextService.TurnSnapshotRequest(
            "conn-1",
            "chat-1",
            "u-4",
            "a-4",
            "Now only cancelled ones.",
            "Now only cancelled ones.",
            "Updated the prior guest booking query to only include cancelled rows.",
            "BI_QUERY",
            "UNIVERSAL_CHAT",
            null,
            null,
            0.84d,
            "run-1",
            true,
            ResolvedConversationContext.empty(),
            "REUSE_SCOPE",
            "agent_trace",
            List.of("Updated prior query with cancelled-booking filter."),
            "verified"
        ));

        String resolvedJson = saved.getResolvedContextJson().toUpperCase();
        assertTrue(resolvedJson.contains("BOOKING_MADE_ON"));
    }

    @Test
    void decideCarryover_marksClarificationAnswerForShortTimeWindowReply() {
        ResolvedConversationContext context = new ResolvedConversationContext(
            "ctx-clarify",
            "BI_QUERY",
            "CLARIFICATION",
            "Show booking revenue trend",
            "Need one clarification: which date range should define the trend?",
            Map.of(
                "tables", List.of("USER_BOOKINGS"),
                "metric", "booking revenue",
                "timeframe", "last 30 days"
            ),
            List.of(),
            Map.of(),
            null,
            List.of(),
            0.91d
        );

        ConversationCarryoverDecision decision = service.decideCarryover(
            "last 30 days",
            new ChatQuestionRoutingService.QuestionRoute(ChatQuestionRoutingService.RouteType.BI_QUERY, ChatQuestionRoutingService.BrainTopic.GENERAL),
            context
        );

        assertEquals(ConversationCarryoverDecision.ReuseMode.ANSWER_CLARIFICATION, decision.reuseMode());
        assertTrue(decision.reusesPriorScope());
        assertEquals("user_bookings", decision.preferredTables().getFirst());
    }

    @Test
    void decideCarryover_marksNewIntentForSchemaQuestionAgainstBiContext() {
        ResolvedConversationContext context = new ResolvedConversationContext(
            "ctx-bi",
            "BI_QUERY",
            "RESOLVED",
            "Show total bookings by hotel",
            "Booking totals by hotel from USER_BOOKINGS joined to HOTEL.",
            Map.of(
                "tables", List.of("USER_BOOKINGS", "HOTEL"),
                "metric", "bookings"
            ),
            List.of(),
            Map.of(),
            "SELECT * FROM USER_BOOKINGS",
            List.of(),
            0.79d
        );

        ConversationCarryoverDecision decision = service.decideCarryover(
            "What columns are there in HOTEL table?",
            new ChatQuestionRoutingService.QuestionRoute(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, ChatQuestionRoutingService.BrainTopic.SCHEMA),
            context
        );

        assertEquals(ConversationCarryoverDecision.ReuseMode.NEW_INTENT, decision.reuseMode());
        assertTrue(decision.isTopicReset());
    }
}
