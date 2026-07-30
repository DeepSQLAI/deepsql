package com.dbaagent.service.brain.core;

import com.dbaagent.model.brain.BrainScore;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.QueryPerformanceRegression;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.brain.*;
import com.dbaagent.repository.*;
import com.dbaagent.repository.brain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for calculating Brain health scores.
 * Implements BRAIN-design.md Section 11: "Scoring Model"
 *
 * Scoring Model:
 * - Schema Design Score (0-100)
 * - Query Quality Score (0-100) ← Enhanced with slow query severity and regressions
 * - Index & Access Score (0-100) ← Key Columns Analysis feeds here
 * - Scalability Score (0-100)
 * - Overall Brain Score = Weighted aggregate (25%, 25%, 30%, 20%)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BrainScoreService {

    private final BrainScoreRepository brainScoreRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final ColumnAntiPatternRepository antiPatternRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final QueryLineageRepository queryLineageRepository;
    private final QueryPerformanceRegressionRepository regressionRepository;

    // Brain 2.0 repositories
    private final WorkloadProfileRepository workloadProfileRepository;
    private final KnobRankingRepository knobRankingRepository;
    private final TuningExperimentRepository tuningExperimentRepository;
    private final ConfigurationObservationRepository observationRepository;
    private final ColumnStatisticsRepository columnStatisticsRepository;
    private final PlanExecutionRepository planExecutionRepository;
    private final PlanPatternRepository planPatternRepository;
    private final BrainLearningProgressRepository learningProgressRepository;
    private final CostCalibrationRepository costCalibrationRepository;

    // Score weights - updated for Brain 2.0 (6 dimensions)
    private static final double SCHEMA_WEIGHT = 0.15;           // Reduced from 0.25
    private static final double QUERY_WEIGHT = 0.15;            // Reduced from 0.25
    private static final double INDEX_WEIGHT = 0.20;            // Reduced from 0.30
    private static final double SCALABILITY_WEIGHT = 0.10;      // Reduced from 0.20
    private static final double CONFIG_TUNING_WEIGHT = 0.15;    // Brain 2.0 NEW
    private static final double QUERY_INTELLIGENCE_WEIGHT = 0.15; // Brain 2.0 NEW
    private static final double WORKLOAD_UNDERSTANDING_WEIGHT = 0.05; // Brain 2.0 NEW
    private static final double LEARNING_PROGRESS_WEIGHT = 0.05; // Brain 2.0 NEW

    /**
     * Calculate and save Brain Score for a connection.
     * Brain 2.0 enhanced with ML-based scoring dimensions.
     */
    public BrainScore calculateBrainScore(String connectionId) {
        log.info("Calculating Brain Score (v2.0) for connection: {}", connectionId);

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> breakdown = new HashMap<>();

        // Calculate original sub-scores
        double schemaScore = calculateSchemaDesignScore(connectionId, breakdown);
        double queryScore = calculateQueryQualityScore(connectionId, breakdown);
        double indexScore = calculateIndexAccessScore(connectionId, breakdown);
        double scalabilityScore = calculateScalabilityScore(connectionId, breakdown);

        // Calculate Brain 2.0 sub-scores
        double configTuningScore = calculateConfigTuningScore(connectionId, breakdown);
        double queryIntelligenceScore = calculateQueryIntelligenceScore(connectionId, breakdown);
        double workloadUnderstandingScore = calculateWorkloadUnderstandingScore(connectionId, breakdown);
        double learningProgressScore = calculateLearningProgressScore(connectionId, breakdown);

        // Calculate overall weighted score (Brain 2.0 enhanced)
        double overallScore = (schemaScore * SCHEMA_WEIGHT) +
                             (queryScore * QUERY_WEIGHT) +
                             (indexScore * INDEX_WEIGHT) +
                             (scalabilityScore * SCALABILITY_WEIGHT) +
                             (configTuningScore * CONFIG_TUNING_WEIGHT) +
                             (queryIntelligenceScore * QUERY_INTELLIGENCE_WEIGHT) +
                             (workloadUnderstandingScore * WORKLOAD_UNDERSTANDING_WEIGHT) +
                             (learningProgressScore * LEARNING_PROGRESS_WEIGHT);

        // Get metadata counts
        long tablesAnalyzed = keyColumnAnalysisRepository.countDistinctTablesByConnectionId(connectionId);
        long columnsAnalyzed = keyColumnAnalysisRepository.countByConnectionId(connectionId);
        long queriesAnalyzed = slowQueryHistoryRepository.countByConnectionId(connectionId) +
                               queryLineageRepository.countByConnectionId(connectionId);

        BrainScore brainScore = BrainScore.builder()
            .connectionId(connectionId)
            .overallScore(BigDecimal.valueOf(overallScore).setScale(2, RoundingMode.HALF_UP))
            .schemaDesignScore(BigDecimal.valueOf(schemaScore).setScale(2, RoundingMode.HALF_UP))
            .queryQualityScore(BigDecimal.valueOf(queryScore).setScale(2, RoundingMode.HALF_UP))
            .indexAccessScore(BigDecimal.valueOf(indexScore).setScale(2, RoundingMode.HALF_UP))
            .scalabilityScore(BigDecimal.valueOf(scalabilityScore).setScale(2, RoundingMode.HALF_UP))
            // Brain 2.0 scores
            .configTuningScore(BigDecimal.valueOf(configTuningScore).setScale(2, RoundingMode.HALF_UP))
            .queryIntelligenceScore(BigDecimal.valueOf(queryIntelligenceScore).setScale(2, RoundingMode.HALF_UP))
            .workloadUnderstandingScore(BigDecimal.valueOf(workloadUnderstandingScore).setScale(2, RoundingMode.HALF_UP))
            .learningProgressScore(BigDecimal.valueOf(learningProgressScore).setScale(2, RoundingMode.HALF_UP))
            .calculatedAt(now)
            .tablesAnalyzed((int) tablesAnalyzed)
            .columnsAnalyzed((int) columnsAnalyzed)
            .queriesAnalyzed((int) queriesAnalyzed)
            .scoreBreakdown(breakdown)
            .build();

        brainScoreRepository.save(brainScore);

        // Update learning progress
        updateLearningProgress(connectionId, breakdown);

        log.info("Brain Score v2.0 calculated: {} (Schema: {}, Query: {}, Index: {}, Scalability: {}, " +
                "ConfigTuning: {}, QueryIntel: {}, Workload: {}, Learning: {})",
            overallScore, schemaScore, queryScore, indexScore, scalabilityScore,
            configTuningScore, queryIntelligenceScore, workloadUnderstandingScore, learningProgressScore);

        return brainScore;
    }

    /**
     * Get the latest Brain Score for a connection.
     */
    public Optional<BrainScore> getLatestBrainScore(String connectionId) {
        return brainScoreRepository.findLatestByConnectionId(connectionId);
    }

    /**
     * Get Brain Score history for trend analysis.
     */
    public List<BrainScore> getBrainScoreHistory(String connectionId, int limit) {
        return brainScoreRepository.findTopByConnectionIdOrderByCalculatedAtDesc(connectionId, limit);
    }

    /**
     * Calculate Schema Design Score (0-100).
     * Placeholder: Will be enhanced with ER model, normalization checks, etc.
     */
    private double calculateSchemaDesignScore(String connectionId, Map<String, Object> breakdown) {
        // For now, return a baseline score
        // Future: Analyze foreign keys, constraints, normalization level
        double score = 75.0;  // Baseline

        Map<String, Object> schemaBreakdown = new HashMap<>();
        schemaBreakdown.put("message", "Schema design scoring not yet implemented");
        schemaBreakdown.put("baseline", score);
        breakdown.put("schemaDesign", schemaBreakdown);

        return score;
    }

    /**
     * Calculate Query Quality Score (0-100).
     * Enhanced scoring based on:
     * - Slow query count and severity (critical/high queries have more impact)
     * - Active performance regressions
     * - Trend analysis (improving vs degrading)
     */
    private double calculateQueryQualityScore(String connectionId, Map<String, Object> breakdown) {
        double score = 100.0;

        // Factor 1: Slow query count penalty
        long totalSlowQueries = slowQueryHistoryRepository.countByConnectionId(connectionId);
        double slowQueryPenalty = 0;
        if (totalSlowQueries > 0) {
            // Base penalty: -5 points per 100 slow queries, max 25
            slowQueryPenalty = Math.min(25, (totalSlowQueries / 100.0) * 5);
        }

        // Factor 2: Slow query severity analysis
        int criticalQueries = 0;
        int highSeverityQueries = 0;
        ObjectMapper objectMapper = new ObjectMapper();
        List<SlowQueryHistory> recentHistories = slowQueryHistoryRepository.findTop10ByConnectionIdOrderByAnalyzedAtDesc(connectionId);
        for (SlowQueryHistory history : recentHistories) {
            if (history.getAnalysisData() != null && !history.getAnalysisData().isBlank()) {
                try {
                    // Parse the JSON analysis data to extract severity counts
                    Map<String, Object> analysis = objectMapper.readValue(
                        history.getAnalysisData(),
                        new TypeReference<Map<String, Object>>() {}
                    );
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> queries = (List<Map<String, Object>>) analysis.get("topSlowQueries");
                    if (queries != null) {
                        for (Map<String, Object> query : queries) {
                            String severity = (String) query.get("severity");
                            if ("CRITICAL".equals(severity)) {
                                criticalQueries++;
                            } else if ("HIGH".equals(severity)) {
                                highSeverityQueries++;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not parse slow query analysis data for severity scoring");
                }
            }
        }

        // Severity penalty: -3 per critical, -1 per high
        double severityPenalty = Math.min(30, (criticalQueries * 3) + (highSeverityQueries * 1));

        // Factor 3: Performance regressions penalty
        List<QueryPerformanceRegression> activeRegressions = regressionRepository
            .findByConnectionIdAndAcknowledgedFalseAndResolvedFalseOrderByDetectedAtDesc(connectionId);
        int criticalRegressions = 0;
        int severeRegressions = 0;
        int moderateRegressions = 0;

        for (QueryPerformanceRegression regression : activeRegressions) {
            switch (regression.getSeverity()) {
                case CRITICAL -> criticalRegressions++;
                case SEVERE -> severeRegressions++;
                case MODERATE -> moderateRegressions++;
                default -> {}
            }
        }

        // Regression penalty: -10 per critical, -5 per severe, -2 per moderate
        double regressionPenalty = Math.min(30,
            (criticalRegressions * 10) + (severeRegressions * 5) + (moderateRegressions * 2));

        // Calculate final score
        score = score - slowQueryPenalty - severityPenalty - regressionPenalty;

        Map<String, Object> queryBreakdown = new HashMap<>();
        queryBreakdown.put("totalSlowQueries", totalSlowQueries);
        queryBreakdown.put("slowQueryPenalty", slowQueryPenalty);
        queryBreakdown.put("criticalSlowQueries", criticalQueries);
        queryBreakdown.put("highSeveritySlowQueries", highSeverityQueries);
        queryBreakdown.put("severityPenalty", severityPenalty);
        queryBreakdown.put("activeRegressions", activeRegressions.size());
        queryBreakdown.put("criticalRegressions", criticalRegressions);
        queryBreakdown.put("severeRegressions", severeRegressions);
        queryBreakdown.put("moderateRegressions", moderateRegressions);
        queryBreakdown.put("regressionPenalty", regressionPenalty);
        queryBreakdown.put("finalScore", Math.max(0, score));
        breakdown.put("queryQuality", queryBreakdown);

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Calculate Index & Access Score (0-100).
     * Based on Key Columns Analysis - this is where our feature feeds in!
     *
     * Scoring factors:
     * - % of high-priority columns that are indexed
     * - Absence of critical anti-patterns
     * - Index usage statistics
     */
    private double calculateIndexAccessScore(String connectionId, Map<String, Object> breakdown) {
        List<KeyColumnAnalysis> analyses = keyColumnAnalysisRepository
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId);

        if (analyses.isEmpty()) {
            Map<String, Object> indexBreakdown = new HashMap<>();
            indexBreakdown.put("message", "No key column analysis data available");
            breakdown.put("indexAccess", indexBreakdown);
            return 0.0;
        }

        // Factor 1: Index coverage for high-priority columns
        long totalHighPriority = analyses.stream()
            .filter(a -> a.getEnhancedImportanceScore() != null &&
                        a.getEnhancedImportanceScore().doubleValue() >= 70)
            .count();

        long indexedHighPriority = analyses.stream()
            .filter(a -> a.getEnhancedImportanceScore() != null &&
                        a.getEnhancedImportanceScore().doubleValue() >= 70 &&
                        a.getIndexName() != null)
            .count();

        double indexCoverage = totalHighPriority > 0 ?
            ((double) indexedHighPriority / totalHighPriority) * 100 : 100.0;

        // Factor 2: Anti-pattern penalty
        long criticalPatterns = antiPatternRepository.countBySeverityAndConnectionId(
            com.dbaagent.model.ColumnAntiPattern.Severity.CRITICAL, connectionId);
        long highPatterns = antiPatternRepository.countBySeverityAndConnectionId(
            com.dbaagent.model.ColumnAntiPattern.Severity.HIGH, connectionId);

        double antiPatternPenalty = Math.min(40, (criticalPatterns * 10) + (highPatterns * 5));

        // Factor 3: Partitioning readiness (bonus)
        long partitionCandidates = analyses.stream()
            .filter(a -> a.getIsPartitionCandidate() != null && a.getIsPartitionCandidate())
            .count();
        double partitionBonus = partitionCandidates > 0 ? Math.min(10, partitionCandidates * 2) : 0;

        // Final score
        double score = indexCoverage - antiPatternPenalty + partitionBonus;
        score = Math.max(0, Math.min(100, score));

        Map<String, Object> indexBreakdown = new HashMap<>();
        indexBreakdown.put("totalHighPriorityColumns", totalHighPriority);
        indexBreakdown.put("indexedHighPriorityColumns", indexedHighPriority);
        indexBreakdown.put("indexCoveragePercent", indexCoverage);
        indexBreakdown.put("criticalAntiPatterns", criticalPatterns);
        indexBreakdown.put("highAntiPatterns", highPatterns);
        indexBreakdown.put("antiPatternPenalty", antiPatternPenalty);
        indexBreakdown.put("partitionCandidates", partitionCandidates);
        indexBreakdown.put("partitionBonus", partitionBonus);
        indexBreakdown.put("finalScore", score);
        breakdown.put("indexAccess", indexBreakdown);

        return score;
    }

    /**
     * Calculate Scalability Score (0-100).
     * Based on table sizes, growth patterns, partitioning readiness.
     */
    private double calculateScalabilityScore(String connectionId, Map<String, Object> breakdown) {
        List<KeyColumnAnalysis> analyses = keyColumnAnalysisRepository
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId);

        if (analyses.isEmpty()) {
            Map<String, Object> scaleBreakdown = new HashMap<>();
            scaleBreakdown.put("message", "No data available");
            breakdown.put("scalability", scaleBreakdown);
            return 75.0;  // Baseline
        }

        // Factor 1: Partitioning readiness
        long partitionCandidates = analyses.stream()
            .filter(a -> a.getIsPartitionCandidate() != null && a.getIsPartitionCandidate())
            .count();

        long largeTablesWithoutPartitioning = analyses.stream()
            .filter(a -> a.getTotalRows() != null && a.getTotalRows() > 10_000_000)
            .map(KeyColumnAnalysis::getTableName)
            .distinct()
            .count();

        double partitioningScore = partitionCandidates > 0 ? 100.0 : 70.0;
        if (largeTablesWithoutPartitioning > 5) {
            partitioningScore -= (largeTablesWithoutPartitioning - 5) * 5;
        }

        // Factor 2: Index on large tables
        long largeTablesIndexed = analyses.stream()
            .filter(a -> a.getTotalRows() != null && a.getTotalRows() > 1_000_000 &&
                        a.getIndexName() != null)
            .map(KeyColumnAnalysis::getTableName)
            .distinct()
            .count();

        double indexOnLargeTablesScore = largeTablesIndexed > 0 ? 100.0 : 60.0;

        // Average of factors
        double score = (partitioningScore + indexOnLargeTablesScore) / 2.0;

        Map<String, Object> scaleBreakdown = new HashMap<>();
        scaleBreakdown.put("partitionCandidates", partitionCandidates);
        scaleBreakdown.put("largeTablesWithoutPartitioning", largeTablesWithoutPartitioning);
        scaleBreakdown.put("partitioningScore", partitioningScore);
        scaleBreakdown.put("largeTablesIndexed", largeTablesIndexed);
        scaleBreakdown.put("indexOnLargeTablesScore", indexOnLargeTablesScore);
        scaleBreakdown.put("finalScore", score);
        breakdown.put("scalability", scaleBreakdown);

        return Math.max(0, Math.min(100, score));
    }

    // =====================================================
    // Brain 2.0 Scoring Methods (OtterTune & optd inspired)
    // =====================================================

    /**
     * Calculate Config Tuning Score (0-100).
     * Based on OtterTune concepts: workload characterization, knob identification,
     * and tuning experiment results.
     */
    private double calculateConfigTuningScore(String connectionId, Map<String, Object> breakdown) {
        double score = 50.0; // Baseline

        Map<String, Object> configBreakdown = new HashMap<>();

        // Factor 1: Knob Ranking completeness (25 points max)
        List<KnobRanking> rankings = knobRankingRepository
            .findByConnectionIdOrderByTargetMetricAscRankAsc(connectionId);
        double knobRankingScore = rankings.isEmpty() ? 0 :
            Math.min(25, rankings.size() * 2.5);
        configBreakdown.put("knobRankingsCount", rankings.size());
        configBreakdown.put("knobRankingScore", knobRankingScore);

        // Factor 2: Configuration observations (25 points max)
        long observationCount = observationRepository.countByConnectionId(connectionId);
        double observationScore = Math.min(25, observationCount * 2.5);
        configBreakdown.put("observationCount", observationCount);
        configBreakdown.put("observationScore", observationScore);

        // Factor 3: Experiment success rate (25 points max)
        List<TuningExperiment> completedExperiments = tuningExperimentRepository
            .findByConnectionIdAndStatus(connectionId, TuningExperiment.ExperimentStatus.COMPLETED);
        double experimentSuccessScore = 0;
        if (!completedExperiments.isEmpty()) {
            long successful = completedExperiments.stream()
                .filter(TuningExperiment::isSuccessful)
                .count();
            double successRate = (double) successful / completedExperiments.size();
            experimentSuccessScore = successRate * 25;
        }
        configBreakdown.put("completedExperiments", completedExperiments.size());
        configBreakdown.put("experimentSuccessScore", experimentSuccessScore);

        // Factor 4: Workload-based optimization (25 points max)
        Optional<WorkloadProfile> profileOpt = workloadProfileRepository.findByConnectionId(connectionId);
        double workloadOptScore = 0;
        if (profileOpt.isPresent()) {
            WorkloadProfile profile = profileOpt.get();
            if (profile.getOptimalConfig() != null && !profile.getOptimalConfig().isEmpty()) {
                workloadOptScore = 25;
            } else if (profile.getClassificationConfidence() != null &&
                      profile.getClassificationConfidence() > 70) {
                workloadOptScore = 15;
            } else {
                workloadOptScore = 10;
            }
        }
        configBreakdown.put("workloadOptimizationScore", workloadOptScore);

        score = knobRankingScore + observationScore + experimentSuccessScore + workloadOptScore;
        configBreakdown.put("finalScore", Math.max(0, Math.min(100, score)));
        breakdown.put("configTuning", configBreakdown);

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Calculate Query Intelligence Score (0-100).
     * Based on optd concepts: cardinality estimation accuracy, adaptive learning,
     * and plan pattern effectiveness.
     */
    private double calculateQueryIntelligenceScore(String connectionId, Map<String, Object> breakdown) {
        double score = 50.0; // Baseline

        Map<String, Object> queryIntBreakdown = new HashMap<>();

        // Factor 1: Cardinality estimation accuracy (30 points max)
        double cardinalityScore = 15; // Default if no data
        try {
            Double avgError = planExecutionRepository.calculateAverageCardinalityError(connectionId);
            if (avgError != null && avgError > 0) {
                // Convert error ratio to score: 1.0 = perfect, 10x error = 50%
                double logError = Math.log10(Math.max(avgError, 1.0 / avgError));
                cardinalityScore = Math.max(0, 30 - logError * 15);
            }
        } catch (Exception e) {
            log.debug("Could not calculate cardinality accuracy: {}", e.getMessage());
        }
        queryIntBreakdown.put("cardinalityScore", cardinalityScore);

        // Factor 2: Plan pattern coverage (30 points max)
        long patternCount = planPatternRepository.countByConnectionId(connectionId);
        long executionCount = planExecutionRepository.countByConnectionId(connectionId);
        double patternCoverage = executionCount > 0 ?
            Math.min(1.0, (double) patternCount / (executionCount * 0.1)) : 0;
        double patternScore = patternCoverage * 30;
        queryIntBreakdown.put("patternCount", patternCount);
        queryIntBreakdown.put("executionCount", executionCount);
        queryIntBreakdown.put("patternScore", patternScore);

        // Factor 3: Column statistics coverage (20 points max)
        long statsCount = columnStatisticsRepository.countByConnectionId(connectionId);
        long columnsAnalyzed = keyColumnAnalysisRepository.countByConnectionId(connectionId);
        double statsCoverage = columnsAnalyzed > 0 ?
            Math.min(1.0, (double) statsCount / columnsAnalyzed) : 0;
        double statsScore = statsCoverage * 20;
        queryIntBreakdown.put("columnStatsCount", statsCount);
        queryIntBreakdown.put("statsScore", statsScore);

        // Factor 4: Cost calibration reliability (20 points max)
        double calibrationScore = 0;
        Optional<CostCalibration> calibrationOpt = costCalibrationRepository.findByConnectionId(connectionId);
        if (calibrationOpt.isPresent()) {
            CostCalibration calibration = calibrationOpt.get();
            calibrationScore = calibration.getConfidence() * 20;
        }
        queryIntBreakdown.put("calibrationScore", calibrationScore);

        score = cardinalityScore + patternScore + statsScore + calibrationScore;
        queryIntBreakdown.put("finalScore", Math.max(0, Math.min(100, score)));
        breakdown.put("queryIntelligence", queryIntBreakdown);

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Calculate Workload Understanding Score (0-100).
     * Measures how well we understand the database workload.
     */
    private double calculateWorkloadUnderstandingScore(String connectionId, Map<String, Object> breakdown) {
        Map<String, Object> workloadBreakdown = new HashMap<>();

        Optional<WorkloadProfile> profileOpt = workloadProfileRepository.findByConnectionId(connectionId);

        if (profileOpt.isEmpty()) {
            workloadBreakdown.put("message", "No workload profile available");
            workloadBreakdown.put("finalScore", 0.0);
            breakdown.put("workloadUnderstanding", workloadBreakdown);
            return 0.0;
        }

        WorkloadProfile profile = profileOpt.get();

        // Factor 1: Classification confidence (40 points max)
        double classificationScore = profile.getClassificationConfidence() != null ?
            profile.getClassificationConfidence() * 0.4 : 0;
        workloadBreakdown.put("classificationConfidence", profile.getClassificationConfidence());
        workloadBreakdown.put("classificationScore", classificationScore);

        // Factor 2: Workload type identification (20 points)
        double typeScore = profile.getWorkloadType() != null &&
            profile.getWorkloadType() != WorkloadProfile.WorkloadType.UNKNOWN ? 20 : 0;
        workloadBreakdown.put("workloadType", profile.getWorkloadType());
        workloadBreakdown.put("typeScore", typeScore);

        // Factor 3: Fingerprint completeness (20 points max)
        double fingerprintScore = 0;
        if (profile.getFingerprintVector() != null && !profile.getFingerprintVector().isEmpty()) {
            fingerprintScore = Math.min(20, profile.getFingerprintVector().size() * 2.5);
        }
        workloadBreakdown.put("fingerprintDimensions",
            profile.getFingerprintVector() != null ? profile.getFingerprintVector().size() : 0);
        workloadBreakdown.put("fingerprintScore", fingerprintScore);

        // Factor 4: Selected metrics coverage (20 points max)
        double metricsScore = 0;
        if (profile.getSelectedMetrics() != null && !profile.getSelectedMetrics().isEmpty()) {
            metricsScore = Math.min(20, profile.getSelectedMetrics().size() * 2.5);
        }
        workloadBreakdown.put("selectedMetricsCount",
            profile.getSelectedMetrics() != null ? profile.getSelectedMetrics().size() : 0);
        workloadBreakdown.put("metricsScore", metricsScore);

        double score = classificationScore + typeScore + fingerprintScore + metricsScore;
        workloadBreakdown.put("finalScore", Math.max(0, Math.min(100, score)));
        breakdown.put("workloadUnderstanding", workloadBreakdown);

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Calculate Learning Progress Score (0-100).
     * Measures overall Brain 2.0 learning progress.
     */
    private double calculateLearningProgressScore(String connectionId, Map<String, Object> breakdown) {
        Map<String, Object> learningBreakdown = new HashMap<>();

        // Get or calculate progress metrics
        double score = 0;
        int totalMilestones = 10;
        int achievedMilestones = 0;

        // Milestone 1: Workload characterized
        Optional<WorkloadProfile> profileOpt = workloadProfileRepository.findByConnectionId(connectionId);
        boolean workloadCharacterized = profileOpt.isPresent() &&
            profileOpt.get().getWorkloadType() != WorkloadProfile.WorkloadType.UNKNOWN;
        if (workloadCharacterized) achievedMilestones++;
        learningBreakdown.put("workloadCharacterized", workloadCharacterized);

        // Milestone 2: Knobs ranked
        List<KnobRanking> rankings = knobRankingRepository
            .findByConnectionIdOrderByTargetMetricAscRankAsc(connectionId);
        boolean knobsRanked = rankings.size() >= 5;
        if (knobsRanked) achievedMilestones++;
        learningBreakdown.put("knobsRanked", knobsRanked);

        // Milestone 3: Baseline observations recorded
        long observations = observationRepository.countByConnectionId(connectionId);
        boolean baselineRecorded = observations >= 5;
        if (baselineRecorded) achievedMilestones++;
        learningBreakdown.put("baselineRecorded", baselineRecorded);

        // Milestone 4: Column statistics collected
        long columnStats = columnStatisticsRepository.countByConnectionId(connectionId);
        boolean statsCollected = columnStats >= 10;
        if (statsCollected) achievedMilestones++;
        learningBreakdown.put("statsCollected", statsCollected);

        // Milestone 5: Plan executions recorded
        long planExecutions = planExecutionRepository.countByConnectionId(connectionId);
        boolean executionsRecorded = planExecutions >= 20;
        if (executionsRecorded) achievedMilestones++;
        learningBreakdown.put("executionsRecorded", executionsRecorded);

        // Milestone 6: Plan patterns discovered
        long patterns = planPatternRepository.countByConnectionId(connectionId);
        boolean patternsDiscovered = patterns >= 5;
        if (patternsDiscovered) achievedMilestones++;
        learningBreakdown.put("patternsDiscovered", patternsDiscovered);

        // Milestone 7: Cost calibration started
        boolean calibrationStarted = costCalibrationRepository.existsByConnectionId(connectionId);
        if (calibrationStarted) achievedMilestones++;
        learningBreakdown.put("calibrationStarted", calibrationStarted);

        // Milestone 8: Cost calibration reliable
        Optional<CostCalibration> calibrationOpt = costCalibrationRepository.findByConnectionId(connectionId);
        boolean calibrationReliable = calibrationOpt.isPresent() && calibrationOpt.get().isReliable();
        if (calibrationReliable) achievedMilestones++;
        learningBreakdown.put("calibrationReliable", calibrationReliable);

        // Milestone 9: Experiments completed
        List<TuningExperiment> experiments = tuningExperimentRepository
            .findByConnectionIdAndStatus(connectionId, TuningExperiment.ExperimentStatus.COMPLETED);
        boolean experimentsCompleted = experiments.size() >= 1;
        if (experimentsCompleted) achievedMilestones++;
        learningBreakdown.put("experimentsCompleted", experimentsCompleted);

        // Milestone 10: Successful experiments
        long successfulExperiments = experiments.stream().filter(TuningExperiment::isSuccessful).count();
        boolean hasSuccessfulExperiment = successfulExperiments > 0;
        if (hasSuccessfulExperiment) achievedMilestones++;
        learningBreakdown.put("hasSuccessfulExperiment", hasSuccessfulExperiment);

        score = ((double) achievedMilestones / totalMilestones) * 100;

        learningBreakdown.put("achievedMilestones", achievedMilestones);
        learningBreakdown.put("totalMilestones", totalMilestones);
        learningBreakdown.put("finalScore", score);
        breakdown.put("learningProgress", learningBreakdown);

        return score;
    }

    /**
     * Update the learning progress entity for tracking.
     */
    private void updateLearningProgress(String connectionId, Map<String, Object> breakdown) {
        try {
            BrainLearningProgress progress = learningProgressRepository.findByConnectionId(connectionId)
                .orElse(BrainLearningProgress.builder()
                    .connectionId(connectionId)
                    .createdAt(LocalDateTime.now())
                    .build());

            // Extract milestones from breakdown
            @SuppressWarnings("unchecked")
            Map<String, Object> learning = (Map<String, Object>) breakdown.get("learningProgress");
            if (learning != null) {
                progress.setWorkloadTypeIdentified(Boolean.TRUE.equals(learning.get("workloadCharacterized")));
                progress.setKnobRankingCompleted(Boolean.TRUE.equals(learning.get("knobsRanked")));
                progress.setBaselineObservationRecorded(Boolean.TRUE.equals(learning.get("baselineRecorded")));

                Integer milestones = (Integer) learning.get("achievedMilestones");
                Integer total = (Integer) learning.get("totalMilestones");
                if (milestones != null && total != null && total > 0) {
                    progress.setReadinessPercent((milestones.doubleValue() / total) * 100);
                    progress.setBrainV2Ready(milestones >= 7); // 70% milestone threshold
                }
            }

            // Update counts from other parts of breakdown
            progress.setColumnStatsCollected((int) columnStatisticsRepository.countByConnectionId(connectionId));
            progress.setPlanExecutionsRecorded((int) planExecutionRepository.countByConnectionId(connectionId));
            progress.setPatternsDiscovered((int) planPatternRepository.countByConnectionId(connectionId));

            List<TuningExperiment> experiments = tuningExperimentRepository
                .findByConnectionIdAndStatus(connectionId, TuningExperiment.ExperimentStatus.COMPLETED);
            progress.setExperimentsCompleted(experiments.size());
            progress.setSuccessfulExperiments((int) experiments.stream()
                .filter(TuningExperiment::isSuccessful).count());

            progress.setUpdatedAt(LocalDateTime.now());

            learningProgressRepository.save(progress);

        } catch (Exception e) {
            log.warn("Could not update learning progress: {}", e.getMessage());
        }
    }
}
