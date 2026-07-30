package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The composed body of a {@link com.dbaagent.model.WorkloadAnalysisReport},
 * serialized to a jsonb column. Each section is sourced from a distinct
 * engine — Workload Analysis only orchestrates and assembles, it does not
 * recompute what the index advisor / optimizer / health probes already produce.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadAnalysisData {

    /** Human-readable workload characterization (top tables, read/write tilt, hotspots). */
    private WorkloadSummary summary;

    /** Holistic index adds — read from IndexRecommendationService top-N (not re-ranked here). */
    @Builder.Default
    private List<IndexRec> indexRecommendations = List.of();

    /** Per-candidate query rewrites — from the rewrites-only QueryOptimizationService. */
    @Builder.Default
    private List<RewriteRec> rewriteRecommendations = List.of();

    /** Pre-aggregation / materialized-view recs — net-new analyzer (populated in Phase 3). */
    @Builder.Default
    private List<PreAggRec> preAggregationRecommendations = List.of();

    /** Index cleanup — unused + duplicate indexes from PerformanceMonitoringService. */
    @Builder.Default
    private List<IndexHealthItem> indexHealth = List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkloadSummary {
        private int candidatesAnalyzed;
        private long totalSlowMsAnalyzed;
        private String analyzedOn;        // the slow_query_run day the candidate set came from
        private String headline;          // one-line characterization for the report header
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexRec {
        private String recommendationId;  // FK to index_recommendations (so the UI can deep-link to apply)
        private String tableName;
        private String columnNames;
        private String createStatement;   // the CREATE INDEX DDL the user can copy/apply
        private String kind;              // CREATE_INDEX | DROP_INDEX
        private String priority;
        private long workloadScoreMs;
        private long writeCostScore;
        private long netBenefit;
        private Double hypopgReductionPct;
        private int contributingQueries;  // evidence count
        private String rationale;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RewriteRec {
        private String fingerprint;
        private String originalQuery;
        private String rewrittenQuery;
        private Double estimatedImprovementPct;
        private String diagnosis;         // plan bottleneck the rewrite addresses
        private String candidateReason;   // why this query was in the set: MOST_AFFECTED | REGRESSED | HIGH_VOLUME
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreAggRec {
        private String suggestedName;     // e.g. mv_daily_revenue_by_region
        private List<String> sourceTables;
        private List<String> dimensions;  // GROUP BY columns
        private List<String> measures;    // aggregate expressions
        private String materializationDdl; // CREATE MATERIALIZED VIEW / summary-table DDL
        private String refreshStrategy;   // e.g. "REFRESH MATERIALIZED VIEW CONCURRENTLY nightly"
        private int sharedByQueries;      // how many candidate queries share this aggregate shape
        private long workloadScoreMs;
        private String rationale;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexHealthItem {
        private String issue;             // UNUSED | DUPLICATE
        private String tableName;
        private String indexName;
        private String detail;            // size, scan count, the index it duplicates, etc.
        private String suggestedAction;   // e.g. "DROP INDEX ..."
    }
}
