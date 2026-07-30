package com.dbaagent.service.pipeline;

import java.util.*;

public record PipelineResult(
    String sql,
    String response,
    boolean historyMatched,
    ResolvedContext resolvedContext,
    ColumnValueContext columnValueContext,
    ValidationResult validationResult,
    List<String> stepsExecuted,
    long totalDurationMs
) {
    public boolean hasSql() {
        return sql != null && !sql.isBlank();
    }
}
