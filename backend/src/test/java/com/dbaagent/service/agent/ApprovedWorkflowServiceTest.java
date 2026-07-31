package com.dbaagent.service.agent;

import com.dbaagent.dto.AgentRunTraceResponse;
import com.dbaagent.model.AgentRun;
import com.dbaagent.model.ApprovedAgentWorkflow;
import com.dbaagent.model.ChatTurnContext;
import com.dbaagent.repository.ApprovedAgentWorkflowRepository;
import com.dbaagent.repository.ChatTurnContextRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovedWorkflowServiceTest {

    @Mock
    private ApprovedAgentWorkflowRepository approvedAgentWorkflowRepository;

    @Mock
    private ChatTurnContextRepository chatTurnContextRepository;

    @Mock
    private AgentRunService agentRunService;

    private ApprovedWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new ApprovedWorkflowService(
            approvedAgentWorkflowRepository,
            chatTurnContextRepository,
            agentRunService,
            new ObjectMapper()
        );
    }

    @Test
    void approveRun_persistsWorkflowSignatureToolsAndParams() {
        AgentRunTraceResponse trace = new AgentRunTraceResponse();
        trace.setConnectionId("conn-1");
        trace.setQuestion("What is the subscription revenue collected from India this month?");
        trace.setIntent("SUBSCRIPTION_REVENUE");
        trace.setGoal("Fetch subscription revenue");
        trace.setPlanSummary("Goal: Fetch subscription revenue");
        trace.setConfidence(0.93);

        AgentRunTraceResponse.StepDto step = new AgentRunTraceResponse.StepDto();
        step.setStepKey("revenue-gross");
        step.setToolName("subscription_revenue_collected_tool");
        step.setParams(Map.of("groupBy", "COUNTRY", "countryFilters", List.of("India")));
        trace.setSteps(List.of(step));

        when(agentRunService.getTrace("run-1")).thenReturn(Optional.of(trace));
        when(approvedAgentWorkflowRepository.findByConnectionIdAndIntentAndQuestionSignature(
            eq("conn-1"),
            eq("SUBSCRIPTION_REVENUE"),
            any(String.class)
        )).thenReturn(Optional.empty());
        when(approvedAgentWorkflowRepository.save(any(ApprovedAgentWorkflow.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<ApprovedAgentWorkflow> saved = service.approveRun("conn-1", "run-1");

        assertTrue(saved.isPresent());
        ArgumentCaptor<ApprovedAgentWorkflow> captor = ArgumentCaptor.forClass(ApprovedAgentWorkflow.class);
        verify(approvedAgentWorkflowRepository).save(captor.capture());
        ApprovedAgentWorkflow workflow = captor.getValue();
        assertEquals("conn-1", workflow.getConnectionId());
        assertEquals("SUBSCRIPTION_REVENUE", workflow.getIntent());
        assertEquals("What is the subscription revenue collected from India this month?", workflow.getExampleQuestion());
        assertTrue(workflow.getNormalizedQuestion().contains("india"));
        assertEquals(1, workflow.getHelpfulCount());
        assertEquals(0.93, workflow.getAverageConfidence());
        assertTrue(workflow.getToolsJson().contains("subscription_revenue_collected_tool"));
        assertTrue(workflow.getStepParamsJson().contains("\"countryFilters\":[\"India\"]"));
    }

    @Test
    void findBestMatch_returnsMostSimilarWorkflow() {
        ApprovedAgentWorkflow workflow = new ApprovedAgentWorkflow();
        workflow.setConnectionId("conn-1");
        workflow.setIntent("CHURN_RISK");
        workflow.setNormalizedQuestion("churn customers dropped customers usage");
        workflow.setHelpfulCount(3);

        when(approvedAgentWorkflowRepository.findByConnectionIdAndIntentOrderByLastApprovedAtDesc("conn-1", "CHURN_RISK"))
            .thenReturn(List.of(workflow));

        Optional<ApprovedWorkflowMatch> match = service.findBestMatch(
            "conn-1",
            AgentIntent.CHURN_RISK,
            "Which customers are about to churn because usage dropped recently?"
        );

        assertTrue(match.isPresent());
        assertSame(workflow, match.get().workflow());
        assertTrue(match.get().similarityScore() >= 0.42d);
    }

    @Test
    void findBestMatch_normalizesEquivalentRelativeTimePhrases() {
        ApprovedAgentWorkflow workflow = new ApprovedAgentWorkflow();
        workflow.setConnectionId("conn-1");
        workflow.setIntent("UNIVERSAL_CHAT");
        workflow.setNormalizedQuestion("guests recurring window7days");
        workflow.setHelpfulCount(2);

        when(approvedAgentWorkflowRepository.findByConnectionIdAndIntentOrderByLastApprovedAtDesc("conn-1", "UNIVERSAL_CHAT"))
            .thenReturn(List.of(workflow));

        Optional<ApprovedWorkflowMatch> match = service.findBestMatch(
            "conn-1",
            AgentIntent.UNIVERSAL_CHAT,
            "give me recurring guests in last 7 days"
        );

        assertTrue(match.isPresent());
        assertSame(workflow, match.get().workflow());
        assertTrue(match.get().similarityScore() >= 0.42d);
    }

    @Test
    void applyApprovedWorkflow_mergesSavedParamsWithoutOverwritingCurrentParams() {
        ApprovedAgentWorkflow workflow = new ApprovedAgentWorkflow();
        workflow.setStepParamsJson("""
            [
              {
                "stepKey":"revenue-gross",
                "toolName":"subscription_revenue_collected_tool",
                "params":{
                  "groupBy":"COUNTRY",
                  "countryFilters":["India"]
                }
              }
            ]
            """);
        ApprovedWorkflowMatch match = new ApprovedWorkflowMatch(workflow, 0.75d);

        AgentPlan plan = new AgentPlan(
            AgentIntent.SUBSCRIPTION_REVENUE,
            "Fetch subscription revenue",
            List.of(new AgentPlanStep(
                "revenue-gross",
                "Fetch collected revenue",
                "subscription_revenue_collected_tool",
                Map.of("periodLabel", "March 2026", "groupBy", "CURRENCY")
            ))
        );

        AgentPlan merged = service.applyApprovedWorkflow(plan, match);

        assertEquals(1, merged.steps().size());
        Map<String, Object> params = merged.steps().getFirst().params();
        assertEquals("CURRENCY", params.get("groupBy"));
        assertEquals("March 2026", params.get("periodLabel"));
        assertEquals(List.of("India"), params.get("countryFilters"));
    }

    @Test
    void approveRun_followUpCorrectionAnchorsWorkflowToRootQuestion() {
        AgentRunTraceResponse trace = new AgentRunTraceResponse();
        trace.setConnectionId("conn-1");
        trace.setChatId("chat-1");
        trace.setAssistantMessageId("assistant-1");
        trace.setQuestion("don't do * 1000 for milli seconds conversion.");
        trace.setIntent("UNIVERSAL_CHAT");
        trace.setGoal("Answer the user's data question with deterministic schema reasoning and safe SQL execution");
        trace.setPlanSummary("Goal: universal chat");
        trace.setConfidence(0.9);

        AgentRunTraceResponse.StepDto step = new AgentRunTraceResponse.StepDto();
        step.setStepKey("universal-chat");
        step.setToolName("universal_chat_tool");
        step.setParams(Map.of("routeType", "BI_QUERY"));
        step.setExecutedSql("SELECT user_email, COUNT(*) FROM customer_orders GROUP BY user_email");
        trace.setSteps(List.of(step));

        AgentRun rootRun = new AgentRun();
        rootRun.setId("run-root");
        rootRun.setChatId("chat-1");
        rootRun.setIntent("UNIVERSAL_CHAT");
        rootRun.setQuestion("find recurring guests in the last one week");

        AgentRun refinementRun = new AgentRun();
        refinementRun.setId("run-refine");
        refinementRun.setChatId("chat-1");
        refinementRun.setIntent("UNIVERSAL_CHAT");
        refinementRun.setQuestion("how about this month?");

        AgentRun approvedRun = new AgentRun();
        approvedRun.setId("run-approved");
        approvedRun.setChatId("chat-1");
        approvedRun.setIntent("UNIVERSAL_CHAT");
        approvedRun.setQuestion("don't do * 1000 for milli seconds conversion.");

        when(agentRunService.getTrace("run-approved")).thenReturn(Optional.of(trace));
        ChatTurnContext turnContext = new ChatTurnContext();
        turnContext.setId("ctx-1");
        turnContext.setChatId("chat-1");
        turnContext.setAnchorQuestion("find recurring guests in the last one week");
        turnContext.setCurrentQuestion("don't do * 1000 for milli seconds conversion.");
        turnContext.setChainSummary("Recurring guests over a one week window using customer_orders.");
        turnContext.setResolvedContextJson("""
            {"tables":["customer_orders"],"metric":"recurring guests","timeframe":"last one week"}
            """);
        when(chatTurnContextRepository.findByAssistantMessageId("assistant-1")).thenReturn(Optional.of(turnContext));
        when(approvedAgentWorkflowRepository.findByConnectionIdAndIntentAndQuestionSignature(
            eq("conn-1"),
            eq("UNIVERSAL_CHAT"),
            any(String.class)
        )).thenReturn(Optional.empty());
        when(approvedAgentWorkflowRepository.save(any(ApprovedAgentWorkflow.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<ApprovedAgentWorkflow> saved = service.approveRun("conn-1", "run-approved");

        assertTrue(saved.isPresent());
        assertEquals("find recurring guests in the last one week", saved.get().getExampleQuestion());
        assertEquals("ctx-1", saved.get().getSourceContextId());
        assertEquals("find recurring guests in the last one week", saved.get().getAnchorQuestion());
        assertTrue(saved.get().getChainSummary().contains("Recurring guests"));
        assertTrue(saved.get().getResolvedContextJson().contains("customer_orders"));
        assertTrue(saved.get().getNormalizedQuestion().contains("recurring"));
        assertTrue(saved.get().getStepParamsJson().contains("\"approvedQuestion\":\"find recurring guests in the last one week\""));
        assertTrue(saved.get().getStepParamsJson().contains("\"approvedChainSummary\""));
        assertTrue(saved.get().getStepParamsJson().contains("\"approvedSql\":\"SELECT user_email, COUNT(*) FROM customer_orders GROUP BY user_email\""));
        verify(approvedAgentWorkflowRepository, times(1)).save(any(ApprovedAgentWorkflow.class));
    }
}
