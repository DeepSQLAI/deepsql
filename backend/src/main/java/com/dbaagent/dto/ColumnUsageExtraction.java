package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of parsing a SQL query for column usage
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnUsageExtraction {
    @Builder.Default
    private List<ColumnUsageDetail> joinColumns = new ArrayList<>();

    @Builder.Default
    private List<ColumnUsageDetail> whereColumns = new ArrayList<>();

    @Builder.Default
    private List<ColumnUsageDetail> groupByColumns = new ArrayList<>();

    @Builder.Default
    private List<ColumnUsageDetail> orderByColumns = new ArrayList<>();

    @Builder.Default
    private List<ColumnUsageDetail> selectColumns = new ArrayList<>();
}
