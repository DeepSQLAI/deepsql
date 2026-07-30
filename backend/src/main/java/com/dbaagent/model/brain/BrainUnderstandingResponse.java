package com.dbaagent.model.brain;
import com.dbaagent.model.TableUnderstandingScore;
import com.dbaagent.model.ColumnUnderstandingScore;

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
public class BrainUnderstandingResponse {
    private String connectionId;
    private Integer overallScore;
    private Integer tableCount;
    private Integer columnCount;
    private LocalDateTime lastTrainingAt;
    private List<TableUnderstandingScore> tables;
    private List<ColumnUnderstandingScore> columns;
    private List<BrainInputRequest> needsInput;
    private List<BrainScoreSnapshot> scoreHistory;
    private Integer trendDelta;
    private String trendDirection;
    private List<BrainAlert> alerts;
    private BrainSchemaDrift schemaDrift;
}
