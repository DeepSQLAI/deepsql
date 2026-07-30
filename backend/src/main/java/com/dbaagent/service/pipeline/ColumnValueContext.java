package com.dbaagent.service.pipeline;

import java.util.*;

public record ColumnValueContext(
    Map<String, List<String>> valueMap,
    String formattedContext,
    long fetchDurationMs,
    List<String> columnsSkipped
) {
    public static ColumnValueContext empty() {
        return new ColumnValueContext(Map.of(), "", 0, List.of());
    }

    public boolean isEmpty() {
        return valueMap.isEmpty();
    }
}
