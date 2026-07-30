package com.dbaagent.service;

import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.TrainingDataEmbedding;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RetrievedContextResult(
    String trainingContext,
    String companyKnowledgeContext,
    String companyKnowledgeDiagnosticsContext,
    @JsonIgnore List<CompanyKnowledgeEntry> relevantCompanyKnowledge,
    Set<String> ragTableNames,
    RetrievalIntent retrievalIntent,
    int retrievalTopK,
    int resultCount,
    long durationMs,
    Map<TrainingDataEmbedding.TrainingDataType, Long> typeCounts,
    boolean skipped,
    String skipReason
) {
    public static RetrievedContextResult skipped(RetrievalIntent retrievalIntent, String skipReason) {
        return new RetrievedContextResult(
            "",
            "",
            "",
            List.of(),
            new HashSet<>(),
            retrievalIntent,
            0,
            0,
            0,
            Map.of(),
            true,
            skipReason
        );
    }
}
