package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableTrainingDetail {
    private String tableName;
    private String schema;
    private String type;
    private Integer columnCount;
    private Integer indexCount;
    private Long rowCount;
    private Long sizeBytes;
    private Integer ddlLength;
    private Long durationMs;
    private Integer embeddingDimensions;
}
