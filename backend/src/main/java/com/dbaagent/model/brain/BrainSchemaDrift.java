package com.dbaagent.model.brain;

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
public class BrainSchemaDrift {
    private LocalDateTime lastSnapshotAt;
    private Integer tablesAdded;
    private Integer tablesRemoved;
    private Integer columnsAdded;
    private Integer columnsRemoved;
    private Integer scoreImpact;
    private List<String> sampleTablesAdded;
    private List<String> sampleTablesRemoved;
    private List<String> sampleColumnsAdded;
    private List<String> sampleColumnsRemoved;
}
