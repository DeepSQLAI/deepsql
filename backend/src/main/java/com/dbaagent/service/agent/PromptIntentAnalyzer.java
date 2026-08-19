package com.dbaagent.service.agent;

import com.dbaagent.util.PatternUtil;
import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.ResolvedConversationContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PromptIntentAnalyzer {

    public PromptIntent analyze(
        String rawQuestion,
        String effectiveQuestion,
        ResolvedConversationContext resolvedConversationContext,
        ChatQuestionRoutingService.QuestionRoute routeHint
    ) {
        String question = effectiveQuestion != null && !effectiveQuestion.isBlank() ? effectiveQuestion : rawQuestion;
        if (question == null || question.isBlank()) {
            return PromptIntent.unsupported();
        }

        String normalized = question.toLowerCase(Locale.ROOT).trim();
        Set<PromptIntent.SubjectType> subjectTypes = detectSubjectTypes(normalized, routeHint);
        PromptIntent.Domain domain = detectDomain(normalized, resolvedConversationContext, routeHint, subjectTypes);
        PromptIntent.RequestedOutput requestedOutput = detectRequestedOutput(normalized, domain);
        PromptIntent.TaskType taskType = detectTaskType(normalized, domain, requestedOutput);

        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("routeType", routeHint != null ? routeHint.type().name() : ChatQuestionRoutingService.RouteType.GENERAL.name());
        constraints.put("brainTopic", routeHint != null ? routeHint.brainTopic().name() : ChatQuestionRoutingService.BrainTopic.GENERAL.name());
        constraints.put("hasMatchedContext", resolvedConversationContext != null && resolvedConversationContext.hasMatchedContext());
        constraints.put("followUp", looksLikeFollowUp(normalized));
        if (resolvedConversationContext != null && resolvedConversationContext.hasMatchedContext()) {
            constraints.put("matchedContextId", resolvedConversationContext.matchedContextId());
            constraints.put("resolvedContext", resolvedConversationContext.resolvedContext());
        }

        boolean requiresSql = domain == PromptIntent.Domain.BI
            && requestedOutput == PromptIntent.RequestedOutput.SQL_RESULT;
        boolean requiresLiveMetadata = domain == PromptIntent.Domain.PERFORMANCE
            && (subjectTypes.contains(PromptIntent.SubjectType.INDEX)
                || subjectTypes.contains(PromptIntent.SubjectType.QUERY)
                || subjectTypes.contains(PromptIntent.SubjectType.WORKLOAD)
                || taskType == PromptIntent.TaskType.MONITOR
                || taskType == PromptIntent.TaskType.TROUBLESHOOT);
        boolean requiresCachedMetadata = domain == PromptIntent.Domain.SCHEMA || domain == PromptIntent.Domain.PERFORMANCE;
        boolean requiresDocs = domain == PromptIntent.Domain.GENERAL
            || subjectTypes.contains(PromptIntent.SubjectType.DOC)
            || normalized.contains("define")
            || normalized.contains("meaning")
            || normalized.contains("what defines");

        return new PromptIntent(
            domain,
            taskType,
            subjectTypes,
            requestedOutput,
            constraints,
            requiresSql,
            requiresLiveMetadata,
            requiresCachedMetadata,
            requiresDocs
        );
    }

    private PromptIntent.Domain detectDomain(
        String normalized,
        ResolvedConversationContext resolvedConversationContext,
        ChatQuestionRoutingService.QuestionRoute routeHint,
        Set<PromptIntent.SubjectType> subjectTypes
    ) {
        if (looksLikePriorQueryDisplayFollowUp(normalized, resolvedConversationContext)) {
            if (looksLikePriorQueryDiagnosticFollowUp(normalized)) {
                return PromptIntent.Domain.PERFORMANCE;
            }
            if (resolvedConversationContext != null
                && "BI_QUERY".equalsIgnoreCase(resolvedConversationContext.matchedRouteType())) {
                return PromptIntent.Domain.BI;
            }
            return PromptIntent.Domain.PERFORMANCE;
        }

        boolean indexRecommendationPrompt = looksLikeIndexRecommendationPrompt(normalized);
        if (indexRecommendationPrompt
            || (subjectTypes.contains(PromptIntent.SubjectType.INDEX) && looksLikeIndexWorkloadRecommendationPrompt(normalized))
            || looksLikeColumnImpactPrompt(normalized, subjectTypes)
            || looksLikePerformancePrompt(normalized)
            || subjectTypes.contains(PromptIntent.SubjectType.WORKLOAD)
            || subjectTypes.contains(PromptIntent.SubjectType.TUNING)
            || subjectTypes.contains(PromptIntent.SubjectType.GROWTH)) {
            return PromptIntent.Domain.PERFORMANCE;
        }

        if (routeHint != null && routeHint.isBiQuery()) {
            return PromptIntent.Domain.BI;
        }

        if (routeHint != null && routeHint.isBrainMetadata()) {
            return switch (routeHint.brainTopic()) {
                case PERFORMANCE, WORKLOAD, TUNING, GROWTH -> PromptIntent.Domain.PERFORMANCE;
                case SCHEMA, KEY_COLUMNS, RELATIONSHIPS, CLASSIFICATION, GENERAL -> PromptIntent.Domain.SCHEMA;
            };
        }

        if (looksLikeBiPrompt(normalized)) {
            return PromptIntent.Domain.BI;
        }

        if (looksLikeSchemaPrompt(normalized, subjectTypes)) {
            return PromptIntent.Domain.SCHEMA;
        }

        return PromptIntent.Domain.GENERAL;
    }

    private boolean looksLikePriorQueryDisplayFollowUp(
        String normalized,
        ResolvedConversationContext resolvedConversationContext
    ) {
        if (normalized == null
            || normalized.isBlank()
            || resolvedConversationContext == null
            || !resolvedConversationContext.hasMatchedContext()) {
            return false;
        }
        if (!(normalized.contains("full query")
            || normalized.contains("full sql")
            || normalized.contains("query text")
            || PatternUtil.containsPattern(normalized, "\\bshow\\b.*\\b(query|sql)\\b")
            || PatternUtil.containsPattern(normalized, "\\bwhat\\b.*\\bquery\\b"))) {
            return false;
        }
        if (resolvedConversationContext.sourceSql() != null && !resolvedConversationContext.sourceSql().isBlank()) {
            return true;
        }
        String anchor = lower(resolvedConversationContext.anchorQuestion());
        String summary = lower(resolvedConversationContext.chainSummary());
        if (anchor.contains("slow query")
            || anchor.contains("slowest query")
            || summary.contains("slow query")
            || summary.contains("slowest query")) {
            return true;
        }
        return resolvedConversationContext.conversationHistory().stream()
            .filter(turn -> turn != null && "assistant".equalsIgnoreCase(turn.role()))
            .map(turn -> lower(turn.content()))
            .anyMatch(content -> content.contains("```sql") || PatternUtil.containsPattern(content, "\\bselect\\b.*\\bfrom\\b"));
    }

    private boolean looksLikePriorQueryDiagnosticFollowUp(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("scan")
            || normalized.contains("rows")
            || normalized.contains("slowness")
            || normalized.contains("slow")
            || normalized.contains("causing")
            || normalized.contains("waiting")
            || normalized.contains("latency")
            || normalized.contains("why");
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private Set<PromptIntent.SubjectType> detectSubjectTypes(
        String normalized,
        ChatQuestionRoutingService.QuestionRoute routeHint
    ) {
        Set<PromptIntent.SubjectType> subjectTypes = new LinkedHashSet<>();
        if (normalized.contains("table")) {
            subjectTypes.add(PromptIntent.SubjectType.TABLE);
        }
        if (normalized.contains("column") || normalized.contains("field")) {
            subjectTypes.add(PromptIntent.SubjectType.COLUMN);
        }
        if (normalized.contains("index") || normalized.contains("indexing") || normalized.contains("indices")) {
            subjectTypes.add(PromptIntent.SubjectType.INDEX);
        }
        if (normalized.contains("relationship") || normalized.contains("join path") || normalized.contains("related table")) {
            subjectTypes.add(PromptIntent.SubjectType.RELATIONSHIP);
        }
        if (normalized.contains("revenue")
            || normalized.contains("booking")
            || normalized.contains("payment")
            || normalized.contains("gmv")
            || normalized.contains("mrr")
            || normalized.contains("arr")
            || normalized.contains("count")) {
            subjectTypes.add(PromptIntent.SubjectType.METRIC);
        }
        if (normalized.contains("query")
            || normalized.contains("queries")
            || normalized.contains("latency")
            || normalized.contains("roi")
            || normalized.contains("cost benefit")
            || normalized.contains("performance action")
            || normalized.contains("fix suggestion")
            || normalized.contains("impactful")
            || normalized.contains("impacting")
            || normalized.contains("performance impact")
            || normalized.contains("execution plan")
            || normalized.contains("plan quality")
            || normalized.contains("cardinality")
            || normalized.contains("statistics")
            || normalized.contains("regress")
            || normalized.contains("wait")
            || normalized.contains("waiting")
            || normalized.contains("pressure")) {
            subjectTypes.add(PromptIntent.SubjectType.QUERY);
        }
        if (normalized.contains("workload") || normalized.contains("oltp") || normalized.contains("olap")) {
            subjectTypes.add(PromptIntent.SubjectType.WORKLOAD);
        }
        if (normalized.contains("tuning") || normalized.contains("knob") || normalized.contains("setting") || normalized.contains("config")) {
            subjectTypes.add(PromptIntent.SubjectType.TUNING);
        }
        if (normalized.contains("growth") || normalized.contains("capacity") || normalized.contains("bloat") || normalized.contains("risk")) {
            subjectTypes.add(PromptIntent.SubjectType.GROWTH);
        }
        if (normalized.contains("define")
            || normalized.contains("definition")
            || normalized.contains("meaning")
            || normalized.contains("what defines")
            || normalized.contains("documentation")) {
            subjectTypes.add(PromptIntent.SubjectType.DOC);
        }
        if (routeHint != null) {
            switch (routeHint.brainTopic()) {
                case KEY_COLUMNS -> subjectTypes.add(PromptIntent.SubjectType.COLUMN);
                case RELATIONSHIPS -> subjectTypes.add(PromptIntent.SubjectType.RELATIONSHIP);
                case CLASSIFICATION -> subjectTypes.add(PromptIntent.SubjectType.TABLE);
                case GROWTH -> subjectTypes.add(PromptIntent.SubjectType.GROWTH);
                case PERFORMANCE -> {
                    subjectTypes.add(PromptIntent.SubjectType.QUERY);
                    if (looksLikeIndexPrompt(normalized)) {
                        subjectTypes.add(PromptIntent.SubjectType.INDEX);
                    }
                }
                case WORKLOAD -> subjectTypes.add(PromptIntent.SubjectType.WORKLOAD);
                case TUNING -> subjectTypes.add(PromptIntent.SubjectType.TUNING);
                default -> {
                }
            }
        }
        return subjectTypes;
    }

    private PromptIntent.RequestedOutput detectRequestedOutput(String normalized, PromptIntent.Domain domain) {
        if (normalized.contains("largest") || normalized.contains("biggest") || normalized.contains("top ")) {
            return PromptIntent.RequestedOutput.RANKING;
        }
        if (looksLikeRecommendationPrompt(normalized)) {
            return PromptIntent.RequestedOutput.RECOMMENDATION;
        }
        if (domain == PromptIntent.Domain.BI) {
            return PromptIntent.RequestedOutput.SQL_RESULT;
        }
        if (normalized.contains("explain") || normalized.contains("define") || normalized.contains("what defines") || normalized.contains("meaning")) {
            return PromptIntent.RequestedOutput.EXPLANATION;
        }
        if (normalized.contains("show") || normalized.contains("list") || normalized.contains("which")) {
            return PromptIntent.RequestedOutput.LIST;
        }
        if (normalized.contains("how many") || normalized.contains("count") || normalized.contains("summary")) {
            return PromptIntent.RequestedOutput.SUMMARY;
        }
        return PromptIntent.RequestedOutput.SUMMARY;
    }

    private PromptIntent.TaskType detectTaskType(
        String normalized,
        PromptIntent.Domain domain,
        PromptIntent.RequestedOutput requestedOutput
    ) {
        if (domain == PromptIntent.Domain.BI) {
            return PromptIntent.TaskType.SQL_QUERY;
        }
        if (looksLikeRecommendationPrompt(normalized)) {
            return PromptIntent.TaskType.RECOMMEND;
        }
        if (normalized.contains("monitor") || normalized.contains("health") || normalized.contains("status")) {
            return PromptIntent.TaskType.MONITOR;
        }
        if (normalized.contains("why") || normalized.contains("troubleshoot") || normalized.contains("root cause")) {
            return PromptIntent.TaskType.TROUBLESHOOT;
        }
        if (requestedOutput == PromptIntent.RequestedOutput.EXPLANATION) {
            return PromptIntent.TaskType.EXPLAIN;
        }
        if (requestedOutput == PromptIntent.RequestedOutput.RANKING || normalized.contains("compare")) {
            return PromptIntent.TaskType.COMPARE;
        }
        if (requestedOutput == PromptIntent.RequestedOutput.CLARIFICATION) {
            return PromptIntent.TaskType.CLARIFY;
        }
        return PromptIntent.TaskType.LOOKUP;
    }

    private boolean looksLikeFollowUp(String normalized) {
        return PatternUtil.containsPattern(normalized, "\\b(these|those|same|that|it|them|above|returned)\\b");
    }

    private boolean looksLikeRecommendationPrompt(String normalized) {
        return normalized.contains("should")
            || normalized.contains("recommend")
            || normalized.contains("need")
            || normalized.contains("roi")
            || normalized.contains("cost benefit")
            || normalized.contains("performance action")
            || normalized.contains("fix suggestion")
            || looksLikeIndexRecommendationPrompt(normalized);
    }

    private boolean looksLikeIndexPrompt(String normalized) {
        return normalized.contains("index")
            || normalized.contains("indices")
            || normalized.contains("indexing");
    }

    private boolean looksLikeIndexRecommendationPrompt(String normalized) {
        return normalized.contains("immediate indexing")
            || normalized.contains("missing index")
            || normalized.contains("missing indexes")
            || normalized.contains("unused index")
            || normalized.contains("duplicate index")
            || PatternUtil.containsPattern(normalized, "\\b(which|what)\\b.*\\b(columns?|fields?|tables?)\\b.*\\b(index|indexes|indices|indexing|indexed)\\b")
            || PatternUtil.containsPattern(normalized, "\\b(index|indexes|indices|indexing|indexed)\\b.*\\b(need|needs|should|recommend|required|missing|urgent|urgently|candidate|prioritize|priority)\\b")
            || PatternUtil.containsPattern(normalized, "\\b(need|needs|should|recommend|required|missing|urgent|urgently|candidate|prioritize|priority)\\b.*\\b(index|indexes|indices|indexing|indexed)\\b");
    }

    private boolean looksLikeIndexWorkloadRecommendationPrompt(String normalized) {
        return PatternUtil.containsPattern(normalized, "\\b(need|needs|should|recommend|required|missing|urgent|urgently|candidate|prioritize|priority)\\b")
            || normalized.contains("current workload")
            || normalized.contains("workload");
    }

    private boolean looksLikePerformancePrompt(String normalized) {
        return PatternUtil.containsPattern(normalized, "\\b(slow query|slow queries|latency|bottleneck|regress|regression|regressions|workload|tuning|health|execution plan|plan quality|performance|pressure|waiting|wait event|wait events|active queries|active query|hot|hottest|usage|used|config knobs?|cardinality|statistics|growth|capacity|risk|roi|cost benefit|performance actions?|fix suggestions?)\\b");
    }

    private boolean looksLikeColumnImpactPrompt(String normalized, Set<PromptIntent.SubjectType> subjectTypes) {
        boolean columnSignal = subjectTypes.contains(PromptIntent.SubjectType.COLUMN)
            || PatternUtil.containsPattern(normalized, "\\b(columns?|fields?)\\b");
        boolean impactSignal = PatternUtil.containsPattern(normalized, "\\b(impact|impactful|impacting|important|critical|hot|hottest|used|usage|pressure|bottleneck)\\b");
        boolean schemaCatalogSignal = PatternUtil.containsPattern(normalized, "\\b(what columns|list columns|show columns|columns are in|has columns)\\b");
        return columnSignal && impactSignal && !schemaCatalogSignal;
    }

    private boolean looksLikeBiPrompt(String normalized) {
        return PatternUtil.containsPattern(normalized, "\\b(revenue|sales|gmv|arr|mrr|bookings?|orders?|customers?|payments?|transactions?|retention|churn|ltv|aov|inventory|pipeline|funnel|conversion)\\b")
            || PatternUtil.containsPattern(normalized, "\\b(show|list|get|count|how many|top|compare|trend|breakdown|summarize)\\b");
    }

    private boolean looksLikeSchemaPrompt(String normalized, Set<PromptIntent.SubjectType> subjectTypes) {
        return PatternUtil.containsPattern(normalized, "\\b(schema|table|tables|view|views|column|columns|fields|indexes?|structure|definition)\\b")
            || subjectTypes.contains(PromptIntent.SubjectType.TABLE)
            || subjectTypes.contains(PromptIntent.SubjectType.COLUMN)
            || subjectTypes.contains(PromptIntent.SubjectType.RELATIONSHIP);
    }
}
