package com.dbaagent.model.brain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainTaskRequest {
    private String connectionId;
    private String taskType;
    private String tableName;
    private String tableSchema;
    private String tableLabel;
    private Integer bcnfScore;
    private List<String> issues;
    private List<String> suggestions;
    private String createdBy;
}
