package com.dbaagent.service.pipeline;

import java.util.*;

public record ResolvedContext(
    List<String> tables,
    Map<String, List<String>> columns,
    List<FilterColumn> filterColumns,
    List<String> joinConditions,
    Confidence confidence
) {
    public enum Confidence { HIGH, MEDIUM, LOW }

    public static ResolvedContext empty() {
        return new ResolvedContext(List.of(), Map.of(), List.of(), List.of(), Confidence.LOW);
    }

    public boolean isEmpty() {
        return tables.isEmpty();
    }
}
