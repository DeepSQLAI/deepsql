package com.dbaagent.service.agent;

import com.dbaagent.model.SchemaMetadata;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AgentPlanner {
    private static final Pattern DEPENDENT_REFERENCE_PATTERN = Pattern.compile(
        "(?i)\\b(those|them|these|that|same|same\\s+scope|same\\s+properties|same\\s+accounts|same\\s+users|same\\s+customers|returned|above|previous|their\\b|all\\s+those)\\b"
    );
    private static final Pattern LOOKUP_PATTERN = Pattern.compile(
        "(?i)\\b(show|list|give|provide|return|details?|breakdown|split|names?|countries?|amounts?|ids?)\\b"
    );
    private static final Pattern ACTION_VERB_PATTERN = Pattern.compile(
        "(?i)\\b(show|list|give|provide|return|get|fetch|find|calculate|compute|compare|identify|breakdown|split)\\b"
    );
    private static final Pattern INVESTIGATIVE_PATTERN = Pattern.compile(
        "(?i)\\b(why|investigate|root cause|drivers?|because|explain)\\b"
    );
    private static final Pattern MULTI_PART_SEPARATOR_PATTERN = Pattern.compile(
        "(?i)(?:\\?+\\s+|;\\s+|\\b(?:also|plus|along with|as well as|then)\\b\\s+)"
    );

    public AgentPlan plan(AgentDecision decision, String question, SchemaMetadata schema) {
        return switch (decision.intent()) {
            case METADATA_ANALYSIS -> buildMetadataAnalysisPlan(question, schema, decision.reason());
            case SUBSCRIPTION_REVENUE, CHURN_RISK, ACCOUNTS_MODULE, UNIVERSAL_CHAT -> buildUniversalChatPlan(question, decision.reason());
            case NONE -> throw new IllegalArgumentException("Cannot plan for NONE intent");
        };
    }

    private AgentPlan buildMetadataAnalysisPlan(String question, SchemaMetadata schema, String brainTopicName) {
        List<String> mentionedTables = extractMentionedTables(question, schema);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("brainTopic", brainTopicName);
        params.put("mentionedTables", mentionedTables);

        List<AgentPlanStep> steps = List.of(
            new AgentPlanStep(
                "metadata-context",
                "Resolve metadata request scope",
                "metadata_context_resolution_tool",
                params
            ),
            new AgentPlanStep(
                "metadata-evidence",
                "Check cached metadata evidence against the requested scope",
                "metadata_evidence_lookup_tool",
                params
            ),
            new AgentPlanStep(
                "live-fallback",
                "Query live database metadata catalogs if cached metadata is insufficient",
                "live_metadata_query_tool",
                params
            ),
            new AgentPlanStep(
                "metadata-synthesis",
                "Synthesize a verified metadata answer",
                "metadata_result_synthesis_tool",
                params
            )
        );

        String tableHint = mentionedTables.isEmpty() ? "" : " for " + String.join(", ", mentionedTables);
        return new AgentPlan(
            AgentIntent.METADATA_ANALYSIS,
            "Analyze " + brainTopicName.toLowerCase(Locale.ROOT).replace('_', ' ') + " metadata" + tableHint,
            steps
        );
    }

    private AgentPlan buildUniversalChatPlan(String question, String routeReason) {
        String routeType = routeReason == null || routeReason.isBlank() ? "GENERAL" : routeReason.toUpperCase(Locale.ROOT);
        boolean dataRequest = looksLikeDataRequest(question) || "BI_QUERY".equals(routeType);
        List<AgentPlanTask> tasks = buildUniversalTasks(question, dataRequest);

        Map<String, Object> resolutionParams = new LinkedHashMap<>();
        resolutionParams.put("routeType", routeType);
        resolutionParams.put("dataRequest", dataRequest);
        resolutionParams.put("questionSummary", summarizeQuestion(question));
        resolutionParams.put("taskCount", tasks.stream().filter(task -> task.kind() != AgentTaskKind.SYNTHESIS).count());

        List<AgentPlanStep> steps = new ArrayList<>();
        steps.add(new AgentPlanStep(
            "context-resolution",
            dataRequest ? "Resolve shared schema and business context" : "Resolve shared schema and conversation context",
            "context_resolution_tool",
            Map.copyOf(resolutionParams),
            null,
            List.of(),
            "context_resolution"
        ));

        for (AgentPlanTask task : tasks) {
            if (task.kind() == AgentTaskKind.SYNTHESIS) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("routeType", routeType);
            params.put("dataRequest", dataRequest);
            params.put("questionSummary", summarizeQuestion(task.question()));
            params.put("taskQuestion", task.question());
            params.put("taskTitle", task.title());
            params.put("taskKind", task.kind().name());
            params.put("outputContract", task.outputContract());
            params.put("partIndex", steps.size());
            params.putAll(task.params());

            steps.add(new AgentPlanStep(
                task.taskId(),
                task.title(),
                task.toolName(),
                Map.copyOf(params),
                task.taskId(),
                task.dependsOn(),
                "task_execution"
            ));
        }

        steps.add(new AgentPlanStep(
            "result-synthesis",
            "Stitch task outputs into the final answer",
            "result_synthesis_tool",
            Map.of(
                "routeType", routeType,
                "dataRequest", dataRequest,
                "taskCount", tasks.size()
            ),
            "synthesis",
            tasks.stream()
                .filter(task -> task.kind() != AgentTaskKind.SYNTHESIS)
                .map(AgentPlanTask::taskId)
                .toList(),
            "result_synthesis"
        ));

        String goal = dataRequest
            ? "Answer the user's request with multi-step schema reasoning, safe SQL execution, and stitched coverage of every requested part"
            : "Answer the user's request with vault-first schema reasoning and bounded agent analysis";
        return new AgentPlan(AgentIntent.UNIVERSAL_CHAT, goal, tasks, List.copyOf(steps));
    }

    private List<AgentPlanTask> buildUniversalTasks(String question, boolean dataRequest) {
        List<String> clauses = splitCompoundQuestion(question);
        if (clauses.isEmpty()) {
            clauses = List.of(question == null ? "" : question.trim());
        }

        List<AgentPlanTask> tasks = new ArrayList<>();
        String priorScopeQuestion = null;
        int index = 1;
        for (String clause : clauses) {
            if (clause == null || clause.isBlank()) {
                continue;
            }
            boolean dependsOnPrevious = priorScopeQuestion != null && DEPENDENT_REFERENCE_PATTERN.matcher(clause).find();
            String rewrittenQuestion = dependsOnPrevious
                ? "For the same result scope as: " + priorScopeQuestion + ". " + clause
                : clause;
            AgentTaskKind kind = classifyTaskKind(rewrittenQuestion, dataRequest);
            String taskId = "task-" + index;
            List<String> dependsOn = dependsOnPrevious ? List.of("task-" + (index - 1)) : List.of();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("subQuestion", rewrittenQuestion);
            params.put("independent", dependsOn.isEmpty());
            params.put("dependencyType", dependsOn.isEmpty() ? "independent" : "result-scoped");

            tasks.add(new AgentPlanTask(
                taskId,
                summarizeTaskTitle(clause, kind),
                kind,
                dependsOn,
                "universal_chat_tool",
                rewrittenQuestion,
                outputContractFor(kind),
                Map.copyOf(params)
            ));
            priorScopeQuestion = rewrittenQuestion;
            index++;
        }

        List<String> synthesisDependsOn = tasks.stream()
            .map(AgentPlanTask::taskId)
            .toList();
        tasks.add(new AgentPlanTask(
            "synthesis",
            "Compose one stitched answer that covers every requested part",
            AgentTaskKind.SYNTHESIS,
            synthesisDependsOn,
            "result_synthesis_tool",
            question,
            "final_answer",
            Map.of("taskCount", tasks.size())
        ));

        return List.copyOf(tasks);
    }

    private List<String> splitCompoundQuestion(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        String normalized = question
            .replace('\n', ' ')
            .replaceAll("\\s+", " ")
            .trim();

        String[] rawParts = MULTI_PART_SEPARATOR_PATTERN.split(normalized);
        List<String> parts = new ArrayList<>();
        for (String rawPart : rawParts) {
            String part = rawPart == null ? "" : rawPart.trim();
            if (part.isBlank()) {
                continue;
            }
            if (!parts.isEmpty() && looksLikeTrailingProjection(part)) {
                int lastIndex = parts.size() - 1;
                parts.set(lastIndex, parts.get(lastIndex) + ". " + part);
                continue;
            }
            parts.add(cleanClause(part));
        }
        return parts.isEmpty() ? List.of(normalized) : List.copyOf(parts);
    }

    private String cleanClause(String clause) {
        String cleaned = clause == null ? "" : clause.trim();
        while (cleaned.endsWith(".") || cleaned.endsWith("?") || cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private boolean looksLikeTrailingProjection(String clause) {
        if (clause == null || clause.isBlank()) {
            return false;
        }
        String normalized = clause.toLowerCase(Locale.ROOT);
        boolean hasActionVerb = ACTION_VERB_PATTERN.matcher(normalized).find()
            || normalized.matches(".*\\b(is|are|was|were|had|have)\\b.*");
        return !hasActionVerb && (normalized.contains(",") || normalized.split("\\s+").length <= 8);
    }

    private AgentTaskKind classifyTaskKind(String clause, boolean dataRequest) {
        if (INVESTIGATIVE_PATTERN.matcher(clause).find() && !LOOKUP_PATTERN.matcher(clause).find()) {
            return AgentTaskKind.CLARIFICATION;
        }
        if (LOOKUP_PATTERN.matcher(clause).find()) {
            return AgentTaskKind.LOOKUP;
        }
        return dataRequest ? AgentTaskKind.DATA_QUERY : AgentTaskKind.LOOKUP;
    }

    private String summarizeTaskTitle(String clause, AgentTaskKind kind) {
        String prefix = switch (kind) {
            case DATA_QUERY -> "Calculate";
            case LOOKUP -> "Fetch";
            case CLARIFICATION -> "Clarify";
            case SYNTHESIS -> "Compose";
        };
        String summary = summarizeQuestion(clause);
        if (summary.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return summary;
        }
        return prefix + ": " + summary;
    }

    private String outputContractFor(AgentTaskKind kind) {
        return switch (kind) {
            case DATA_QUERY -> "headline_metric_or_aggregate";
            case LOOKUP -> "detail_rows";
            case CLARIFICATION -> "targeted_clarification";
            case SYNTHESIS -> "final_answer";
        };
    }

    private List<String> extractMentionedTables(String question, SchemaMetadata schema) {
        if (question == null || schema == null || schema.getTables() == null) {
            return List.of();
        }

        String normalized = " " + question
            .toLowerCase(Locale.ROOT)
            .replace('`', ' ')
            .replaceAll("[^a-z0-9_ ]", " ")
            .replaceAll("\\s+", " ")
            .trim() + " ";

        return schema.getTables().stream()
            .map(t -> t.getName())
            .filter(name -> name != null)
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .filter(tableName -> {
                String lower = tableName.toLowerCase(Locale.ROOT);
                String spaced = lower.replace('_', ' ');
                return normalized.contains(" " + lower + " ")
                    || normalized.contains(" " + spaced + " ");
            })
            .distinct()
            .toList();
    }

    private boolean looksLikeDataRequest(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(show|list|give|get|find|fetch|retrieve|count|how many|top|bottom|trend|breakdown|compare|volume|sql|configured|details?|amounts?).*")
            || normalized.matches(".*(bookings?|orders?|payments?|revenue|customers?|users?|sessions?|queries?|rows?|records?|gmv|arr|mrr|fees?|taxes|refunds?|cancellations?|services?|usage|activity|events?|logs?).*");
    }

    private String summarizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String normalized = question.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }
}
