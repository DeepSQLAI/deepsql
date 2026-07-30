package com.dbaagent.service.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MetadataRequestScope(
    Mode mode,
    FactType factType,
    List<String> requestedTables,
    List<String> requestedColumns,
    boolean pairScoped,
    boolean exact,
    String originalQuestion,
    AnswerStyle answerStyle
) {
    public enum Mode {
        STRICT_FACT,
        ANALYTIC_METADATA
    }

    public enum FactType {
        TABLE_COLUMNS,
        TABLE_ROW_COUNT,
        TABLE_INDEXES,
        TABLE_KEY_COLUMNS,
        RELATIONSHIPS,
        JOIN_COLUMNS,
        PERFORMANCE_COLUMN_IMPACT,
        CLASSIFICATION,
        SCHEMA_DELTA,
        GENERAL
    }

    public enum AnswerStyle {
        FACTUAL,
        EXPLANATORY
    }

    public MetadataRequestScope {
        requestedTables = requestedTables == null ? List.of() : List.copyOf(requestedTables);
        requestedColumns = requestedColumns == null ? List.of() : List.copyOf(requestedColumns);
        originalQuestion = originalQuestion == null ? "" : originalQuestion;
        answerStyle = answerStyle == null ? AnswerStyle.FACTUAL : answerStyle;
    }

    public MetadataRequestScope(
        Mode mode,
        FactType factType,
        List<String> requestedTables,
        List<String> requestedColumns,
        boolean pairScoped,
        boolean exact,
        String originalQuestion
    ) {
        this(mode, factType, requestedTables, requestedColumns, pairScoped, exact, originalQuestion, AnswerStyle.FACTUAL);
    }

    public static MetadataRequestScope empty(String question) {
        return new MetadataRequestScope(Mode.ANALYTIC_METADATA, FactType.GENERAL, List.of(), List.of(), false, false, question, AnswerStyle.FACTUAL);
    }

    public boolean isStrictFact() {
        return mode == Mode.STRICT_FACT;
    }

    public boolean hasRequestedTables() {
        return !requestedTables.isEmpty();
    }

    public boolean isSingleTableScoped() {
        return requestedTables.size() == 1;
    }

    public boolean prefersExplanation() {
        return answerStyle == AnswerStyle.EXPLANATORY;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode.name());
        data.put("factType", factType.name());
        data.put("requestedTables", requestedTables);
        data.put("requestedColumns", requestedColumns);
        data.put("pairScoped", pairScoped);
        data.put("exact", exact);
        data.put("answerStyle", answerStyle.name());
        if (!originalQuestion.isBlank()) {
            data.put("originalQuestion", originalQuestion);
        }
        return Map.copyOf(data);
    }
}
