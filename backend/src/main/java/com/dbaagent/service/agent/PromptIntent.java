package com.dbaagent.service.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record PromptIntent(
    Domain domain,
    TaskType taskType,
    Set<SubjectType> subjectTypes,
    RequestedOutput requestedOutput,
    Map<String, Object> constraints,
    boolean requiresSql,
    boolean requiresLiveMetadata,
    boolean requiresCachedMetadata,
    boolean requiresDocs
) {
    public enum Domain {
        SCHEMA,
        PERFORMANCE,
        BI,
        GENERAL,
        UNSUPPORTED
    }

    public enum TaskType {
        LOOKUP,
        EXPLAIN,
        MONITOR,
        RECOMMEND,
        COMPARE,
        SQL_QUERY,
        TROUBLESHOOT,
        CLARIFY
    }

    public enum SubjectType {
        TABLE,
        COLUMN,
        INDEX,
        RELATIONSHIP,
        METRIC,
        QUERY,
        WORKLOAD,
        TUNING,
        GROWTH,
        DOC
    }

    public enum RequestedOutput {
        LIST,
        RANKING,
        SUMMARY,
        EXPLANATION,
        RECOMMENDATION,
        SQL_RESULT,
        CLARIFICATION
    }

    public PromptIntent {
        subjectTypes = subjectTypes == null ? Set.of() : Set.copyOf(subjectTypes);
        constraints = constraints == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(constraints));
    }

    public static PromptIntent unsupported() {
        return new PromptIntent(
            Domain.UNSUPPORTED,
            TaskType.CLARIFY,
            Set.of(),
            RequestedOutput.CLARIFICATION,
            Map.of(),
            false,
            false,
            false,
            false
        );
    }

    public boolean isMetadataDomain() {
        return domain == Domain.SCHEMA || domain == Domain.PERFORMANCE;
    }

    public boolean isIndexFocused() {
        return subjectTypes.contains(SubjectType.INDEX);
    }

    public boolean isRelationshipFocused() {
        return subjectTypes.contains(SubjectType.RELATIONSHIP);
    }

    public boolean isMetricFocused() {
        return subjectTypes.contains(SubjectType.METRIC);
    }

    public PromptIntent resolvedForDomain(Domain resolvedDomain) {
        Domain nextDomain = resolvedDomain == null ? domain : resolvedDomain;
        RequestedOutput nextOutput = nextDomain == Domain.BI
            ? RequestedOutput.SQL_RESULT
            : (requestedOutput == RequestedOutput.SQL_RESULT ? RequestedOutput.SUMMARY : requestedOutput);
        TaskType nextTaskType = nextDomain == Domain.BI
            ? TaskType.SQL_QUERY
            : (taskType == TaskType.SQL_QUERY ? TaskType.LOOKUP : taskType);
        boolean nextRequiresSql = nextDomain == Domain.BI && nextOutput == RequestedOutput.SQL_RESULT;
        boolean nextRequiresLiveMetadata = nextDomain == Domain.PERFORMANCE
            && (subjectTypes.contains(SubjectType.INDEX)
                || subjectTypes.contains(SubjectType.QUERY)
                || subjectTypes.contains(SubjectType.WORKLOAD)
                || nextTaskType == TaskType.MONITOR
                || nextTaskType == TaskType.TROUBLESHOOT);
        boolean nextRequiresCachedMetadata = nextDomain == Domain.SCHEMA || nextDomain == Domain.PERFORMANCE;
        boolean nextRequiresDocs = nextDomain == Domain.GENERAL
            || subjectTypes.contains(SubjectType.DOC)
            || nextTaskType == TaskType.EXPLAIN;
        return new PromptIntent(
            nextDomain,
            nextTaskType,
            subjectTypes,
            nextOutput,
            constraints,
            nextRequiresSql,
            nextRequiresLiveMetadata,
            nextRequiresCachedMetadata,
            nextRequiresDocs
        );
    }
}
