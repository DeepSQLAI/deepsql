package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbaStats {
    private ConnectionStats connectionStats;
    private List<TableStats> tableStats = new ArrayList<>();
    private QueryPerformanceStats queryPerformance;
    private DatabaseHealth health;
}
