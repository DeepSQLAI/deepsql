package com.dbaagent.service.agent;

import java.util.List;

public record SourcePlan(
    List<String> sourceFamilies
) {
    public SourcePlan {
        sourceFamilies = sourceFamilies == null ? List.of() : List.copyOf(sourceFamilies);
    }
}
