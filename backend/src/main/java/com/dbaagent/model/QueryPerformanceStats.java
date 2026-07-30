package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryPerformanceStats {
    private List<SlowQuery> slowQueries = new ArrayList<>();
    private Double avgQueryTime;
    private Long totalQueries;
}
