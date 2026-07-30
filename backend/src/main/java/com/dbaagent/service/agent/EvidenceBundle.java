package com.dbaagent.service.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record EvidenceBundle(
    PromptIntent.Domain intentDomain,
    String evidenceKind,
    Source source,
    String answerType,
    List<Map<String, Object>> primaryRows,
    Map<String, Object> structuredPayload,
    double coverage,
    double confidence,
    String freshness,
    String sourceQuery,
    Set<String> supportingObjectNames,
    boolean sufficient,
    String insufficiencyMessage
) {
    public enum Source {
        SCHEMA_SNAPSHOT,
        TABLE_CLASSIFICATION,
        KEY_COLUMN_ANALYSIS,
        COLUMN_ANTI_PATTERN,
        INFERRED_RELATIONSHIP,
        INDEX_RECOMMENDATION,
        COMPOSITE_INDEX_RECOMMENDATION,
        PERFORMANCE_ACTION,
        QUERY_LINEAGE,
        WORKLOAD_PROFILE,
        KNOB_RANKING,
        CAPACITY_FORECAST,
        ACTIVE_QUERY_SNAPSHOT,
        PLAN_REGRESSION,
        PERFORMANCE_VAULT,
        SLOW_QUERY,
        COMPANY_KNOWLEDGE,
        SEMANTIC_MODEL,
        LIVE_METADATA,
        SQL_RESULT
    }

    public EvidenceBundle {
        primaryRows = primaryRows == null ? List.of() : List.copyOf(primaryRows);
        structuredPayload = structuredPayload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(structuredPayload));
        supportingObjectNames = supportingObjectNames == null ? Set.of() : Set.copyOf(supportingObjectNames);
    }

    public static EvidenceBundle sufficient(
        PromptIntent.Domain intentDomain,
        String evidenceKind,
        Source source,
        String answerType,
        List<Map<String, Object>> primaryRows,
        Map<String, Object> structuredPayload,
        double coverage,
        double confidence,
        String freshness,
        String sourceQuery,
        Set<String> supportingObjectNames
    ) {
        return new EvidenceBundle(
            intentDomain,
            evidenceKind,
            source,
            answerType,
            primaryRows,
            structuredPayload,
            coverage,
            confidence,
            freshness,
            sourceQuery,
            supportingObjectNames,
            true,
            null
        );
    }

    public static EvidenceBundle insufficient(
        PromptIntent.Domain intentDomain,
        String evidenceKind,
        Source source,
        String answerType,
        Map<String, Object> structuredPayload,
        double coverage,
        double confidence,
        String freshness,
        Set<String> supportingObjectNames,
        String insufficiencyMessage
    ) {
        return new EvidenceBundle(
            intentDomain,
            evidenceKind,
            source,
            answerType,
            List.of(),
            structuredPayload,
            coverage,
            confidence,
            freshness,
            null,
            supportingObjectNames,
            false,
            insufficiencyMessage
        );
    }
}
