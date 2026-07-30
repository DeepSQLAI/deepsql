package com.dbaagent.model;
import com.dbaagent.model.brain.BrainScoreReason;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableUnderstandingScore {
    private String tableName;
    private String schemaName;
    private String type;
    private Integer score;
    private Integer columnCount;
    private Integer indexCount;
    private Long rowCount;
    private Long sizeBytes;
    private Integer usageCount;
    private Boolean documented;
    private Boolean hasPrimaryKey;
    private Integer profiledColumns;
    private Integer bcnfScore;
    private String bcnfStatus;
    private List<String> bcnfIssues;
    private List<BrainScoreReason> scoreReasons;
}
