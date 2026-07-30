package com.dbaagent.service;

import java.util.List;

public record ConversationCarryoverDecision(
    ReuseMode reuseMode,
    String matchedContextId,
    String matchedRouteType,
    String matchedStateStatus,
    List<String> preferredTables,
    List<String> preferredJoinPath,
    String preferredTemporalColumn,
    String preferredMetric,
    List<String> preferredFilters,
    double reuseConfidence,
    String rationale
) {
    public enum ReuseMode {
        NONE,
        NARROW_EXISTING_SCOPE,
        ANSWER_CLARIFICATION,
        NEW_INTENT
    }

    public ConversationCarryoverDecision {
        reuseMode = reuseMode == null ? ReuseMode.NONE : reuseMode;
        preferredTables = preferredTables == null ? List.of() : List.copyOf(preferredTables);
        preferredJoinPath = preferredJoinPath == null ? List.of() : List.copyOf(preferredJoinPath);
        preferredFilters = preferredFilters == null ? List.of() : List.copyOf(preferredFilters);
        rationale = rationale == null ? "" : rationale;
    }

    public static ConversationCarryoverDecision empty() {
        return new ConversationCarryoverDecision(
            ReuseMode.NONE,
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            null,
            List.of(),
            0.0d,
            ""
        );
    }

    public boolean reusesPriorScope() {
        return reuseMode == ReuseMode.NARROW_EXISTING_SCOPE || reuseMode == ReuseMode.ANSWER_CLARIFICATION;
    }

    public boolean isTopicReset() {
        return reuseMode == ReuseMode.NEW_INTENT;
    }
}
