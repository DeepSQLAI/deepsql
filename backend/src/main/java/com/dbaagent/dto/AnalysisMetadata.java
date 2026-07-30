package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisMetadata {
    private LocalDateTime analyzedAt;
    private Integer queriesAnalyzed;
    private Boolean isStale;
    private Integer newQueriesSinceAnalysis;
    private Integer lookbackDays;
}
