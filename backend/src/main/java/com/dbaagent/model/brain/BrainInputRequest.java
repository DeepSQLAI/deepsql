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
public class BrainInputRequest {
    private String objectType;
    private String tableName;
    private String columnName;
    private Integer score;
    private String reason;
    private List<String> ambiguousTables;
}
