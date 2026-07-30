package com.dbaagent.service.agent;

public record AgentDecision(boolean useAgenticFlow, AgentIntent intent, String reason) {
    public static AgentDecision none() {
        return new AgentDecision(false, AgentIntent.NONE, "not_applicable");
    }
}
