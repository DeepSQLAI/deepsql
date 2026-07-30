package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyColumnAnalysisResult {
    private List<KeyColumnScore> topColumns;
    private Integer totalColumnsAnalyzed;
    private Integer antiPatternsDetected;
    private LocalDateTime analyzedAt;
    private AnalysisMetadata metadata;
}
