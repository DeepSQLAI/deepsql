package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableStats {
    private String tableName;
    private Long sizeBytes;
    private Long rowCount;
    private Long indexSizeBytes;
    private Double growthRate;
    private String engine;
    private String collation;
    private String comment;
    private Long dataSize;
    private Long indexSize;

    // Bloat metrics
    private Long allocatedBytes;
    private Long bloatBytes;
    private Double bloatPercent;
}
