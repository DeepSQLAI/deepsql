package com.dbaagent.service.agent;

import com.dbaagent.model.AgentRun;
import com.dbaagent.model.QueryResult;
import com.dbaagent.service.ChatQuestionRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock private AgentPromptClassifier promptClassifier;
    @Mock private AgentPlanner planner;
    @Mock private AgentToolRegistry toolRegistry;
    @Mock private AgentAnswerComposer answerComposer;
    @Mock private AgentRunService agentRunService;
    @Mock private ApprovedWorkflowService approvedWorkflowService;
    @Mock private LlmOrchestrationService llmOrchestrationService;
    @Mock private AgentTool tool;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AgentOrchestrator(
            promptClassifier,
            planner,
            toolRegistry,
            answerComposer,
            agentRunService,
            approvedWorkflowService,
            llmOrchestrationService,
            6
        );
    }

    @Test
    void execute_emitsLiveProgressEventsAndAppliesApprovedWorkflow() {
        AgentDecision decision = AgentDecision.none();
        OrchestrationDecision orchestrationDecision = new OrchestrationDecision(
            PromptIntent.Domain.BI,
            "Fetch collected subscription revenue",
            OrchestrationDecision.ThreadMode.FRESH,
            false,
            "",
            List.of("Resolve shared context", "Reason through the request", "Compose final answer"),
            new OrchestrationAction(
                "Start with the shared context.",
                "context_resolution_tool",
                Map.of(),
                "We need retrieval context first.",
                "Resolved shared context",
                false,
                false,
                ""
            ),
            List.of("Return verified SQL-backed revenue"),
            0.89,
            "LLM",
            "The request is a BI query and should use the agentic BI tool chain."
        );
        AgentRun run = new AgentRun();
        run.setId("run-1");
        ApprovedWorkflowMatch workflowMatch = new ApprovedWorkflowMatch(new com.dbaagent.model.ApprovedAgentWorkflow(), 0.81d);
        QueryResult result = new QueryResult();
        result.setColumns(List.of("dimension_key", "collected_amount"));
        result.setRows(List.of(List.of("India", "6076566")));

        when(approvedWorkflowService.findBestMatch("conn-1", AgentIntent.UNIVERSAL_CHAT, "what is the subscription revenue collected from India this month"))
            .thenReturn(Optional.of(workflowMatch));
        when(approvedWorkflowService.applyApprovedWorkflow(any(AgentPlan.class), eq(workflowMatch)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(approvedWorkflowService.workflowHint(workflowMatch)).thenReturn("Using a previously approved workflow (2 helpful votes, similarity 81%)");
        when(agentRunService.startRun(eq("conn-1"), eq(null), eq("what is the subscription revenue collected from India this month"), any(AgentPlan.class)))
            .thenReturn(run);
        when(llmOrchestrationService.decideInitial(any(), any())).thenReturn(orchestrationDecision);
        when(llmOrchestrationService.verifyProgress(any(), any(), anyList(), any())).thenReturn(new VerificationDecision(
            true,
            false,
            true,
            false,
            false,
            0.91,
            "The evidence is ready for synthesis.",
            "",
            new VerificationReport(
                true,
                false,
                null,
                0.91,
                0.88,
                VerificationReport.SourceStrength.HIGH,
                VerificationReport.RecommendedFallback.NONE,
                List.of("SQL result is relevant.")
            )
        ));
        when(llmOrchestrationService.decideNextAction(any(), any(), any(), anyList(), any(), anyInt(), eq(false)))
            .thenReturn(new OrchestrationAction(
                "Run the SQL reasoning tool next.",
                "universal_chat_tool",
                Map.of(),
                "We have enough context to execute the BI request.",
                "A SQL-backed task result",
                false,
                false,
                ""
            ))
            .thenReturn(new OrchestrationAction(
                "Synthesize the final answer.",
                "result_synthesis_tool",
                Map.of(),
                "The evidence is ready for final composition.",
                "Final answer",
                false,
                false,
                ""
            ));
        when(toolRegistry.getRequired("context_resolution_tool")).thenReturn(tool);
        when(toolRegistry.getRequired("universal_chat_tool")).thenReturn(tool);
        when(toolRegistry.getRequired("result_synthesis_tool")).thenReturn(tool);
        when(tool.name()).thenReturn("context_resolution_tool", "universal_chat_tool", "result_synthesis_tool");
        when(tool.execute(any(), any())).thenReturn(new AgentToolResult(
            new AgentObservation("context_resolution", "Resolved shared retrieval context", Map.of("retrievalIntent", "GENERAL")),
            null,
            null,
            0.84
        )).thenReturn(new AgentToolResult(
            new AgentObservation("revenue_snapshot", "Collected revenue grouped by country", Map.of("countries", List.of("India"), "status", "COMPLETED")),
            result,
            "SELECT ...",
            0.95
        )).thenReturn(new AgentToolResult(
            new AgentObservation("result_synthesis", "Composed stitched answer", Map.of("completedTaskCount", 1)),
            result,
            "SELECT ...",
            0.93
        ));
        when(answerComposer.compose(any(AgentPlan.class), any())).thenReturn(new AgentExecutionResult(
            null,
            AgentIntent.UNIVERSAL_CHAT,
            "India: 6076566",
            result,
            "Goal: Fetch collected subscription revenue",
            List.of("SELECT ..."),
            List.of("subscription_revenue_collected_tool"),
            0.93
        ));

        List<AgentProgressEvent> events = new ArrayList<>();
        Optional<AgentExecutionResult> execution = orchestrator.execute(
            true,
            "conn-1",
            "what is the subscription revenue collected from India this month",
            null,
            decision,
            events::add
        );

        assertTrue(execution.isPresent());
        assertEquals("run-1", execution.get().runId());
        assertTrue(events.size() >= 8);
        assertEquals("planning", events.get(0).key());
        assertEquals("active", events.get(0).status());
        assertEquals("planning", events.get(1).key());
        assertEquals("completed", events.get(1).status());
        assertTrue(events.get(1).detail().contains("previously approved workflow"));
        assertEquals("step-1", events.get(2).key());
        assertEquals("active", events.get(2).status());
        assertEquals("step-1", events.get(3).key());
        assertEquals("completed", events.get(3).status());
        assertEquals("composing", events.get(events.size() - 2).key());
        assertEquals("active", events.get(events.size() - 2).status());
        assertEquals("composing", events.get(events.size() - 1).key());
        assertEquals("completed", events.get(events.size() - 1).status());
        assertEquals(0.93, events.get(events.size() - 1).confidence());

        verify(approvedWorkflowService).applyApprovedWorkflow(any(AgentPlan.class), eq(workflowMatch));
        verify(llmOrchestrationService).decideInitial(any(), any());
        verify(agentRunService, atLeast(1)).recordStep(eq("run-1"), anyInt(), any(AgentPlanStep.class), any(AgentToolResult.class));
        verify(agentRunService).completeRun(eq("run-1"), any(AgentExecutionResult.class));
    }

    @Test
    void previewDecision_returnsNoneWhenAgenticDisabled() {
        AgentDecision decision = orchestrator.previewDecision(
            false,
            "which customers are about to churn",
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BI_QUERY,
                ChatQuestionRoutingService.BrainTopic.GENERAL
            )
        );

        assertFalse(decision.useAgenticFlow());
        assertEquals(AgentIntent.NONE, decision.intent());
    }
}
