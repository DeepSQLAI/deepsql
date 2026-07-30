package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import com.dbaagent.service.SqlExecutionPipeline;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ResultSynthesisTool implements AgentTool {

    private static final Pattern SQL_BLOCK_PATTERN = Pattern.compile("```sql[\\s\\S]*?```", Pattern.CASE_INSENSITIVE);
    private final ChatClient chatClient;
    private final SqlExecutionPipeline sqlExecutionPipeline;

    public ResultSynthesisTool(ChatModel chatModel, SqlExecutionPipeline sqlExecutionPipeline) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.sqlExecutionPipeline = sqlExecutionPipeline;
    }

    @Override
    public String name() {
        return "result_synthesis_tool";
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        List<AgentTaskResult> taskResults = context.taskResults().stream()
            .filter(task -> task.kind() != AgentTaskKind.SYNTHESIS)
            .toList();
        List<AgentPlanTask> plannedTasks = ((List<AgentPlanTask>) context.getMemory("planTasks"));
        if (taskResults.isEmpty()) {
            EvidenceLedger ledger = EvidenceLedger.from(context);
            SourcePlan sourcePlan = context.getMemory("sourcePlan");
            String fallback = buildNoTaskSourceReport(ledger, sourcePlan);
            context.putMemory("universalMessage", fallback);
            context.putMemory("universalConfidence", 0.45d);
            return new AgentToolResult(
                new AgentObservation(
                    "result_synthesis",
                    "No task outputs were available to compose the final answer",
                    Map.of("completedTaskCount", 0, "blockedTaskCount", 0)
                ),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                0.45
            );
        }

        String finalMessage = sanitizeFinalAnswer(synthesizeFinalAnswer(context.question(), plannedTasks, taskResults));
        if (finalMessage.isBlank()) {
            finalMessage = fallbackSynthesis(taskResults);
        }

        QueryResult primaryResult = taskResults.stream()
            .map(AgentTaskResult::primaryResult)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
        double confidence = taskResults.stream()
            .mapToDouble(AgentTaskResult::confidence)
            .average()
            .orElse(0.55d);

        context.putMemory("universalMessage", finalMessage);
        context.putMemory("universalPrimaryResult", primaryResult);
        context.putMemory("universalConfidence", confidence);
        context.putMemory("universalResultSets", taskResults);

        long blockedTaskCount = taskResults.stream().filter(task -> !task.completed()).count();
        return new AgentToolResult(
            new AgentObservation(
                "result_synthesis",
                "Composed a stitched answer across " + taskResults.size() + " task output(s)",
                Map.of(
                    "completedTaskCount", taskResults.stream().filter(AgentTaskResult::completed).count(),
                    "blockedTaskCount", blockedTaskCount
                )
            ),
            primaryResult,
            null,
            List.of(),
            primaryResult == null ? List.of() : List.of(primaryResult),
            List.of(new AgentToolArtifact(
                "task_summary",
                "final-synthesis",
                Map.of(
                    "taskCount", taskResults.size(),
                    "blockedTaskCount", blockedTaskCount,
                    "confidence", confidence
                )
            )),
            confidence
        );
    }

    private String buildNoTaskSourceReport(EvidenceLedger ledger, SourcePlan sourcePlan) {
        List<String> attempted = ledger == null ? List.of() : ledger.attemptedSourceFamilies().stream().toList();
        List<String> planned = sourcePlan == null ? List.of() : sourcePlan.sourceFamilies();
        if (attempted.isEmpty() && planned.isEmpty()) {
            return "DeepSQL did not receive any usable tool output for this run. The agent runtime should retry source scouting before returning a final answer.";
        }
        StringBuilder sb = new StringBuilder("DeepSQL did not get enough task evidence to produce a verified final answer.\n\n");
        if (!attempted.isEmpty()) {
            sb.append("**Sources checked**: ").append(String.join(", ", attempted.stream().limit(8).toList())).append(".\n");
        }
        List<String> remaining = planned.stream()
            .filter(source -> attempted.stream().noneMatch(attemptedSource -> attemptedSource.equalsIgnoreCase(source)))
            .limit(6)
            .toList();
        if (!remaining.isEmpty()) {
            sb.append("**Sources still needed**: ").append(String.join(", ", remaining)).append(".\n");
        }
        return sb.toString().trim();
    }

    private String synthesizeFinalAnswer(
        String originalQuestion,
        List<AgentPlanTask> plannedTasks,
        List<AgentTaskResult> taskResults
    ) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(
            "You are composing the final answer for a multi-part analytics workflow.\n" +
                "Rules:\n" +
                "- Cover every planned task in order.\n" +
                "- Use only the executed results and task summaries provided below.\n" +
                "- Do not invent new SQL or mention unexecuted SQL.\n" +
                "- If some tasks are blocked, answer the completed parts first and end with one targeted clarification for the first blocked task.\n" +
                "- Keep the answer concise and user-facing."
        ));
        messages.add(new UserMessage(buildSynthesisPrompt(originalQuestion, plannedTasks, taskResults)));
        return chatClient.prompt().messages(messages).call().content();
    }

    private String buildSynthesisPrompt(
        String originalQuestion,
        List<AgentPlanTask> plannedTasks,
        List<AgentTaskResult> taskResults
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Original request:\n").append(originalQuestion).append("\n\n");
        if (plannedTasks != null && !plannedTasks.isEmpty()) {
            prompt.append("Planned tasks:\n");
            plannedTasks.stream()
                .filter(task -> task.kind() != AgentTaskKind.SYNTHESIS)
                .forEach(task -> prompt
                    .append("- ")
                    .append(task.taskId())
                    .append(": ")
                    .append(task.title())
                    .append(" [")
                    .append(task.kind())
                    .append("]")
                    .append(task.dependsOn().isEmpty() ? "" : " depends on " + String.join(", ", task.dependsOn()))
                    .append('\n'));
            prompt.append('\n');
        }

        prompt.append("Task outputs:\n");
        for (AgentTaskResult taskResult : taskResults) {
            prompt.append("=== ")
                .append(taskResult.taskId())
                .append(" | ")
                .append(taskResult.title())
                .append(" | ")
                .append(taskResult.status())
                .append(" ===\n");
            prompt.append("Summary: ").append(taskResult.summary()).append('\n');
            if (taskResult.message() != null && !taskResult.message().isBlank()) {
                prompt.append("Message: ").append(taskResult.message()).append('\n');
            }
            if (!taskResult.executedQueries().isEmpty()) {
                prompt.append("Executed SQL count: ").append(taskResult.executedQueries().size()).append('\n');
            }
            if (taskResult.primaryResult() != null) {
                prompt.append("Result preview:\n")
                    .append(sqlExecutionPipeline.formatQueryResultForAI(taskResult.primaryResult()))
                    .append('\n');
            }
            prompt.append('\n');
        }
        return prompt.toString().trim();
    }

    private String sanitizeFinalAnswer(String message) {
        if (message == null) {
            return "";
        }
        String sanitized = SQL_BLOCK_PATTERN.matcher(message).replaceAll("").trim();
        return sanitized.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String fallbackSynthesis(List<AgentTaskResult> taskResults) {
        List<String> parts = new ArrayList<>();
        AgentTaskResult firstBlocked = null;
        for (AgentTaskResult taskResult : taskResults) {
            if (taskResult.completed()) {
                if (taskResult.message() != null && !taskResult.message().isBlank()) {
                    parts.add(taskResult.message().trim());
                } else if (taskResult.primaryResult() != null) {
                    parts.add(taskResult.title() + ":\n" + sqlExecutionPipeline.formatQueryResultForAI(taskResult.primaryResult()));
                }
            } else if (firstBlocked == null) {
                firstBlocked = taskResult;
            }
        }

        if (firstBlocked != null && firstBlocked.message() != null && !firstBlocked.message().isBlank()) {
            parts.add(firstBlocked.message().trim());
        }

        return String.join("\n\n", parts).trim();
    }
}
