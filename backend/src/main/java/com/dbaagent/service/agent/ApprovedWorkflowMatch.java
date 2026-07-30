package com.dbaagent.service.agent;

import com.dbaagent.model.ApprovedAgentWorkflow;

public record ApprovedWorkflowMatch(
    ApprovedAgentWorkflow workflow,
    double similarityScore
) {
}
