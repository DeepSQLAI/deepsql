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
public class ColumnUnderstandingScore {
    private String tableName;
    private String columnName;
    private Integer score;
    private String dataType;
    private Boolean nullable;
    private Boolean primaryKey;
    private Boolean indexed;
    private Boolean documented;
    private Integer usageCount;
    private Integer ambiguityCount;
    private List<String> ambiguousTables;
    private Boolean ambiguityResolved;
    private String preferredTable;
    private Long totalRows;
    private Long nullCount;
    private Double nullPercent;
    private Long distinctCount;
    private String minValue;
    private String maxValue;
    private String sampleValue;
    private java.time.LocalDateTime profiledAt;
    private List<BrainScoreReason> scoreReasons;
}
