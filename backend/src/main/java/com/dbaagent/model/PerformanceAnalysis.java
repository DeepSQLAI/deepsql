package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceAnalysis {

    private String connectionId;
    private String dbType;
    private OverallHealth overallHealth;
    private List<IndexRecommendation> indexRecommendations;
    private List<DatabaseRecommendation> generalRecommendations;
    private List<SlowQueryAnalysis> slowQueries;
    private Map<String, Object> databaseMetrics;
    private String aiSummary;

    public enum OverallHealth {
        EXCELLENT,  // No issues detected
        GOOD,       // Minor optimizations possible
        FAIR,       // Some issues need attention
        POOR,       // Significant issues
        CRITICAL    // Urgent action required
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlowQueryAnalysis {
        private String query;
        private Double averageTime;
        private Long executionCount;
        private String tableName;
        private List<String> issues;
        private String optimizedQuery;
        private String explanation;
    }
}
