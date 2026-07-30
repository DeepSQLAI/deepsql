package com.dbaagent.service.agent;

import java.util.List;
import java.util.Map;

public record MetadataEvidenceMatch(
    List<String> requestedTables,
    List<String> matchedTables,
    boolean scopeSatisfied,
    String scopeGapReason
) {
    public MetadataEvidenceMatch {
        requestedTables = requestedTables == null ? List.of() : List.copyOf(requestedTables);
        matchedTables = matchedTables == null ? List.of() : List.copyOf(matchedTables);
        scopeGapReason = scopeGapReason == null ? "" : scopeGapReason;
    }

    public Map<String, Object> toMap() {
        return Map.of(
            "requestedTables", requestedTables,
            "matchedTables", matchedTables,
            "scopeSatisfied", scopeSatisfied,
            "scopeGapReason", scopeGapReason
        );
    }
}
