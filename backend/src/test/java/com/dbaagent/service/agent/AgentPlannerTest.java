package com.dbaagent.service.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlannerTest {

    private final AgentPlanner planner = new AgentPlanner();

    @Test
    void plan_subscriptionRevenuePrompt_buildsUniversalWorkflow() {
        AgentPlan plan = planner.plan(
            new AgentDecision(true, AgentIntent.SUBSCRIPTION_REVENUE, "BI_QUERY"),
            "What is the subscription revenue collected this month in INR and USD?",
            null
        );

        assertEquals(AgentIntent.UNIVERSAL_CHAT, plan.intent());
        assertEquals(1, plan.tasks().stream().filter(task -> task.kind() != AgentTaskKind.SYNTHESIS).count());
        assertEquals(3, plan.steps().size());
        assertEquals("context_resolution_tool", plan.steps().get(0).toolName());
        assertEquals("universal_chat_tool", plan.steps().get(1).toolName());
        assertEquals("result_synthesis_tool", plan.steps().get(2).toolName());
        assertEquals("BI_QUERY", plan.steps().get(1).params().get("routeType"));
        assertEquals(Boolean.TRUE, plan.steps().get(1).params().get("dataRequest"));
    }

    @Test
    void plan_churnPrompt_buildsUniversalWorkflow() {
        AgentPlan plan = planner.plan(
            new AgentDecision(true, AgentIntent.CHURN_RISK, "BI_QUERY"),
            "Which customers are about to churn because usage dropped?",
            null
        );

        assertEquals(AgentIntent.UNIVERSAL_CHAT, plan.intent());
        assertEquals(1, plan.tasks().stream().filter(task -> task.kind() != AgentTaskKind.SYNTHESIS).count());
        assertEquals(3, plan.steps().size());
        assertEquals("universal_chat_tool", plan.steps().get(1).toolName());
        assertEquals("BI_QUERY", plan.steps().get(1).params().get("routeType"));
        assertEquals(Boolean.TRUE, plan.steps().get(1).params().get("dataRequest"));
    }

    @Test
    void plan_genericBiPrompt_buildsUniversalWorkflow() {
        AgentPlan plan = planner.plan(
            new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "BI_QUERY"),
            "give me top 5 hotels by bookings volume in the last 3 days",
            null
        );

        assertEquals(AgentIntent.UNIVERSAL_CHAT, plan.intent());
        assertEquals(1, plan.tasks().stream().filter(task -> task.kind() != AgentTaskKind.SYNTHESIS).count());
        assertEquals(3, plan.steps().size());
        assertEquals("universal_chat_tool", plan.steps().get(1).toolName());
        assertEquals("BI_QUERY", plan.steps().get(1).params().get("routeType"));
        assertEquals(Boolean.TRUE, plan.steps().get(1).params().get("dataRequest"));
    }

    @Test
    void plan_multiPartPrompt_decomposesIntoDependentTasks() {
        AgentPlan plan = planner.plan(
            new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "BI_QUERY"),
            "what is the MRR contributed by new properties in last month? also, show me details of all those properties. Hotel name, country, subscription amount.",
            null
        );

        assertEquals(AgentIntent.UNIVERSAL_CHAT, plan.intent());
        assertEquals(3, plan.tasks().size());
        assertEquals(4, plan.steps().size());
        assertEquals("task-1", plan.tasks().get(0).taskId());
        assertEquals("task-2", plan.tasks().get(1).taskId());
        assertEquals(List.of("task-1"), plan.tasks().get(1).dependsOn());
        assertEquals(AgentTaskKind.SYNTHESIS, plan.tasks().get(2).kind());
        assertEquals("context_resolution_tool", plan.steps().get(0).toolName());
        assertEquals("universal_chat_tool", plan.steps().get(1).toolName());
        assertEquals("universal_chat_tool", plan.steps().get(2).toolName());
        assertEquals("result_synthesis_tool", plan.steps().get(3).toolName());
        assertEquals(List.of("task-1"), plan.steps().get(2).dependsOn());
    }

    @Test
    void plan_joinedProjectionPrompt_keepsSingleDataTask() {
        AgentPlan plan = planner.plan(
            new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "BI_QUERY"),
            "Show booking taxes along with the booking amounts from USER_BOOKINGS",
            null
        );

        assertEquals(1, plan.tasks().stream().filter(task -> task.kind() != AgentTaskKind.SYNTHESIS).count());
        assertEquals(3, plan.steps().size());
        assertEquals("universal_chat_tool", plan.steps().get(1).toolName());
        assertTrue(((String) plan.steps().get(1).params().get("taskQuestion")).contains("booking amounts"));
    }

    @Test
    void plan_metadataRelationshipPrompt_buildsVerifiedMetadataWorkflow() {
        AgentPlan plan = planner.plan(
            new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "RELATIONSHIPS"),
            "How are ORDERS and ORDER_DETAIL related?",
            null
        );

        assertEquals(AgentIntent.METADATA_ANALYSIS, plan.intent());
        assertEquals(4, plan.steps().size());
        assertEquals("metadata_context_resolution_tool", plan.steps().get(0).toolName());
        assertEquals("metadata_evidence_lookup_tool", plan.steps().get(1).toolName());
        assertEquals("live_metadata_query_tool", plan.steps().get(2).toolName());
        assertEquals("metadata_result_synthesis_tool", plan.steps().get(3).toolName());
        assertEquals("RELATIONSHIPS", plan.steps().get(0).params().get("brainTopic"));
    }
}
