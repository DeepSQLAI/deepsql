package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AntiPatternSummary {
    private String id;
    private String patternType;
    private String severity;
    private String title;
    private String description;
    private String recommendation;
    private Integer affectedQueriesCount;
    private String status;
}
