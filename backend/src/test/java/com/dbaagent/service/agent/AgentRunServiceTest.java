package com.dbaagent.service.agent;

import com.dbaagent.model.AgentObservationEntity;
import com.dbaagent.model.AgentRun;
import com.dbaagent.model.AgentRunStep;
import com.dbaagent.repository.AgentObservationRepository;
import com.dbaagent.repository.AgentRunRepository;
import com.dbaagent.repository.AgentRunStepRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentRunStepRepository agentRunStepRepository;
    @Mock private AgentObservationRepository agentObservationRepository;

    private AgentRunService agentRunService;

    @BeforeEach
    void setUp() {
        agentRunService = new AgentRunService(
            agentRunRepository,
            agentRunStepRepository,
            agentObservationRepository,
            new ObjectMapper()
        );
    }

    @Test
    void recordStep_persistsParamsSqlAndObservation() {
        AgentRunStep savedStep = new AgentRunStep();
        savedStep.setId("step-1");
        when(agentRunStepRepository.save(any(AgentRunStep.class))).thenReturn(savedStep);

        AgentPlanStep step = new AgentPlanStep(
            "plan-step-1",
            "Fetch collected revenue",
            "subscription_revenue_collected_tool",
            Map.of("groupBy", "CURRENCY", "periodLabel", "March 2026")
        );
        AgentToolResult result = new AgentToolResult(
            new AgentObservation("revenue_snapshot", "Collected revenue grouped by currency", Map.of("currencies", List.of("INR", "USD"))),
            null,
            "SELECT currency, SUM(amount) FROM PAYMENT_LEDGER",
            0.93
        );

        agentRunService.recordStep("run-1", 0, step, result);

        ArgumentCaptor<AgentRunStep> stepCaptor = ArgumentCaptor.forClass(AgentRunStep.class);
        verify(agentRunStepRepository).save(stepCaptor.capture());
        assertEquals("run-1", stepCaptor.getValue().getRunId());
        assertEquals(0, stepCaptor.getValue().getStepIndex());
        assertTrue(stepCaptor.getValue().getParamsJson().contains("CURRENCY"));
        assertEquals("SELECT currency, SUM(amount) FROM PAYMENT_LEDGER", stepCaptor.getValue().getExecutedSql());

        ArgumentCaptor<AgentObservationEntity> observationCaptor = ArgumentCaptor.forClass(AgentObservationEntity.class);
        verify(agentObservationRepository).save(observationCaptor.capture());
        assertEquals("step-1", observationCaptor.getValue().getStepId());
        assertEquals("revenue_snapshot", observationCaptor.getValue().getObservationType());
        assertTrue(observationCaptor.getValue().getDataJson().contains("USD"));
    }

    @Test
    void getTrace_returnsStructuredStepsAndObservationData() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setConnectionId("conn-1");
        run.setChatId("chat-1");
        run.setQuestion("What revenue was collected?");
        run.setIntent("SUBSCRIPTION_REVENUE");
        run.setGoal("Measure collected revenue");
        run.setPlanSummary("Goal: Measure collected revenue");
        run.setStatus("COMPLETED");
        run.setConfidence(0.93);
        run.setFinalMessage("Answer");
        run.setPlanTasksJson("""
            [
              {"taskId":"task-1","title":"Calculate MRR","kind":"DATA_QUERY","dependsOn":[]},
              {"taskId":"task-2","title":"List properties","kind":"LOOKUP","dependsOn":["task-1"]}
            ]
            """);
        when(agentRunRepository.findById("run-1")).thenReturn(Optional.of(run));

        AgentRunStep step = new AgentRunStep();
        step.setId("step-1");
        step.setRunId("run-1");
        step.setStepIndex(0);
        step.setStepKey("revenue-gross");
        step.setTaskId("task-1");
        step.setTitle("Fetch collected revenue");
        step.setToolName("subscription_revenue_collected_tool");
        step.setStepKind("task_execution");
        step.setStatus("COMPLETED");
        step.setParamsJson("{\"groupBy\":\"CURRENCY\"}");
        step.setExecutedSql("SELECT ...");
        step.setExecutedSqlJson("[\"SELECT ...\",\"SELECT detail ...\"]");
        step.setDependsOnJson("[\"task-0\"]");
        step.setArtifactsJson("""
            [{"artifactType":"task_result","key":"task-1","payload":{"summary":"MRR total"}}]
            """);
        step.setConfidence(0.93);
        when(agentRunStepRepository.findByRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of(step));

        AgentObservationEntity observation = new AgentObservationEntity();
        observation.setId("obs-1");
        observation.setRunId("run-1");
        observation.setStepId("step-1");
        observation.setObservationType("revenue_snapshot");
        observation.setSummary("Collected revenue grouped by currency");
        observation.setDataJson("{\"currencies\":[\"INR\",\"USD\"]}");
        when(agentObservationRepository.findByRunIdOrderByCreatedAtAsc("run-1")).thenReturn(List.of(observation));

        var trace = agentRunService.getTrace("run-1");

        assertTrue(trace.isPresent());
        assertEquals("SUBSCRIPTION_REVENUE", trace.get().getIntent());
        assertEquals(2, trace.get().getTasks().size());
        assertEquals(1, trace.get().getSteps().size());
        assertEquals("subscription_revenue_collected_tool", trace.get().getSteps().getFirst().getToolName());
        assertEquals("task-1", trace.get().getSteps().getFirst().getTaskId());
        assertEquals(List.of("task-0"), trace.get().getSteps().getFirst().getDependsOn());
        assertEquals(List.of("SELECT ...", "SELECT detail ..."), trace.get().getSteps().getFirst().getExecutedQueries());
        assertEquals("task_result", trace.get().getSteps().getFirst().getArtifacts().getFirst().get("artifactType"));
        assertEquals("CURRENCY", trace.get().getSteps().getFirst().getParams().get("groupBy"));
        assertEquals("revenue_snapshot", trace.get().getSteps().getFirst().getObservation().getType());
        assertEquals(List.of("INR", "USD"), trace.get().getSteps().getFirst().getObservation().getData().get("currencies"));
    }
}
