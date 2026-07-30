package com.dbaagent.service.agent;

import java.util.List;
import java.util.stream.Collectors;

public record AgentPlan(AgentIntent intent, String goal, List<AgentPlanTask> tasks, List<AgentPlanStep> steps) {
    public AgentPlan(AgentIntent intent, String goal, List<AgentPlanStep> steps) {
        this(intent, goal, List.of(), steps);
    }

    public AgentPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public String summarize() {
        String renderedTasks = tasks.isEmpty() ? "" : tasks.stream()
            .map(task -> {
                String dependencyText = task.dependsOn().isEmpty()
                    ? ""
                    : " depends on " + String.join(", ", task.dependsOn());
                return task.taskId() + ". " + task.title() + " [" + task.kind().name() + "]" + dependencyText;
            })
            .collect(Collectors.joining("\n"));
        String renderedSteps = steps.stream()
            .map(step -> "- " + step.title() + " (" + step.toolName() + ")")
            .collect(Collectors.joining("\n"));

        StringBuilder summary = new StringBuilder("Goal: ").append(goal);
        if (!renderedTasks.isBlank()) {
            summary.append("\nTasks:\n").append(renderedTasks);
        }
        if (!renderedSteps.isBlank()) {
            summary.append("\nSteps:\n").append(renderedSteps);
        }
        return summary.toString();
    }
}
