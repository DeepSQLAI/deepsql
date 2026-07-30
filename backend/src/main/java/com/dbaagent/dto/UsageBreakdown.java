package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageBreakdown {
    private Integer joinCount;
    private Integer whereCount;
    private Integer groupByCount;
    private Integer orderByCount;
    private Integer totalUsage;

    // Source breakdown
    private Integer slowQueryUsage;
    private Integer lineageUsage;
    private Integer performanceHistoryUsage;

    // Unique query count
    private Integer distinctQueriesCount;
}
