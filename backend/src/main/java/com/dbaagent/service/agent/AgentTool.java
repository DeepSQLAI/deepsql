package com.dbaagent.service.agent;

public interface AgentTool {
    String name();
    AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context);
}
