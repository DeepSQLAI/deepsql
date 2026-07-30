package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingRunSummary {
    private Integer embeddingCount;
    private Integer embeddedDocumentCount;
    private Integer azureDocumentCount;
    private Integer embeddingDimensions;
    private Map<String, Integer> documentCountsByType;
    private Map<String, Integer> embeddedDocumentCountsByType;
}
