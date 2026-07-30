package com.dbaagent.service.agent;

import java.util.List;

public record VerificationReport(
    boolean passed,
    boolean verifiedInsufficiency,
    String failureReason,
    double intentMatchScore,
    double coverageScore,
    SourceStrength sourceStrength,
    RecommendedFallback recommendedFallback,
    List<String> notes
) {
    public enum SourceStrength {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum RecommendedFallback {
        NONE,
        LIVE_METADATA,
        PERFORMANCE_ADVISOR,
        SQL_REPAIR,
        CLARIFY
    }

    public VerificationReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean accepted() {
        return passed || verifiedInsufficiency;
    }
}
