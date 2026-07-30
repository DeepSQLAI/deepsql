package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Consolidated slow-query workload insights response.
 * Covers remediation, hotspots, skew, tail risk, and plan drift.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowQueryInsightsResponse {

    private InsightMetadata metadata;
    private RemediationInsights remediation;
    private HotspotInsights hotspots;
    private SkewInsights skew;
    private TailRiskInsights tailRisk;
    private PlanDriftInsights planDrift;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InsightMetadata {
        private Instant generatedAt;
        private String window;
        private int historiesAnalyzed;
        private int queryObservationsAnalyzed;
        private int cappedAnalyses;
        private double dataCoveragePct;
        private String confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemediationInsights {
        private List<RemediationItem> items;
        private long totalEstimatedSavingsMs;
        private int highConfidenceCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemediationItem {
        private String queryId;
        private String queryPreview;
        private String queryType;
        private List<String> affectedTables;
        private long totalDbTimeMs;
        private double avgExecutionTimeMs;
        private long callCount;
        private String rootCause;
        private double estimatedImprovementPct;
        private long expectedSavingsMs;
        private double priorityScore;
        private String severityMix;
        private String confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HotspotInsights {
        private List<ScanWasteHotspot> scanWaste;
        private List<LockHotspot> lockHotspots;
        private boolean lockDataAvailable;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScanWasteHotspot {
        private String scope;
        private String queryId;
        private String tableName;
        private double wasteRatio;
        private double wasteScore;
        private long rowsExamined;
        private long rowsSent;
        private long totalDbTimeMs;
        private String recommendation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LockHotspot {
        private String scope;
        private String queryId;
        private String tableName;
        private double totalLockWaitMs;
        private double avgLockWaitMs;
        private double lockAmplification;
        private int sampleCount;
        private String riskLevel;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkewInsights {
        private List<SkewItem> items;
        private boolean capped;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkewItem {
        private String id;
        private String tableName;
        private String columnName;
        private String displayValue;
        private int slowQueryCount;
        private int criticalCount;
        private int highCount;
        private double dominancePct;
        private double blastRadiusScore;
        private String riskLevel;
        private List<String> matchingQueryIds;
        private String worstQueryPreview;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TailRiskInsights {
        private List<TailRiskItem> tailRisks;
        private List<BurstWindow> burstWindows;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TailRiskItem {
        private String queryId;
        private String queryPreview;
        private double p50Ms;
        private double p95Ms;
        private double p99Ms;
        private double tailRatio;
        private double coefficientOfVariation;
        private double burstFactor;
        private long totalDbTimeMs;
        private double riskScore;
        private String recommendation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BurstWindow {
        private LocalDateTime windowStart;
        private long totalDbTimeMs;
        private long totalCallCount;
        private List<String> topQueryIds;
        private String riskLevel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanDriftInsights {
        private int totalPlanDriftQueries;
        private int criticalRegressions;
        private boolean fromPlanComparisonTable;
        private List<PlanDriftItem> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanDriftItem {
        private String queryId;
        private String queryPreview;
        private String previousPlanSignature;
        private String currentPlanSignature;
        private int planChangeCount;
        private double costChangePct;
        private double runtimeRegressionPct;
        private String severity;
        private LocalDateTime detectedAt;
        private String source;
        private String notes;
    }
}
