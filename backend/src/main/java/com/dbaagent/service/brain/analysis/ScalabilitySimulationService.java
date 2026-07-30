package com.dbaagent.service.brain.analysis;

import com.dbaagent.model.*;
import com.dbaagent.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for simulating database scalability under growth scenarios.
 * Implements BRAIN-design.md Section 10: "Scalability & Growth Reasoning"
 *
 * Simulates performance at 2X, 5X, 10X, and 20X data growth.
 * Identifies scalability risks and provides recommendations.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScalabilitySimulationService {

    private final ScalabilitySimulationRepository simulationRepository;
    private final TableGrowthPredictionRepository predictionRepository;
    private final TableStatsHistoryRepository tableStatsHistoryRepository;
    private final ColumnProfileRepository columnProfileRepository;
    private final QueryAntiPatternRepository queryAntiPatternRepository;

    /**
     * Run scalability simulation for all growth scenarios.
     */
    public List<ScalabilitySimulation> simulateAllScenarios(String connectionId) {
        log.info("Running scalability simulations for connection: {}", connectionId);

        List<ScalabilitySimulation> simulations = new ArrayList<>();

        for (ScalabilitySimulation.GrowthScenario scenario : ScalabilitySimulation.GrowthScenario.values()) {
            ScalabilitySimulation simulation = simulateGrowth(connectionId, scenario);
            simulations.add(simulation);
        }

        log.info("Completed {} scalability simulations", simulations.size());
        return simulations;
    }

    /**
     * Simulate growth for a specific scenario.
     */
    public ScalabilitySimulation simulateGrowth(String connectionId, ScalabilitySimulation.GrowthScenario scenario) {
        log.info("Simulating {} growth for connection: {}", scenario.getLabel(), connectionId);

        // Get current metrics
        List<TableStatsHistory> currentStats = tableStatsHistoryRepository.findLatestSnapshotsForConnection(connectionId);
        if (currentStats.isEmpty()) {
            log.warn("No table stats found for connection: {}", connectionId);
            return createEmptySimulation(connectionId, scenario);
        }

        // Calculate current state
        long currentTotalRows = currentStats.stream()
            .mapToLong(ts -> ts.getRowCount() != null ? ts.getRowCount() : 0L)
            .sum();
        long currentTotalSizeMb = currentStats.stream()
            .mapToLong(ts -> ts.getSizeBytes() != null ? ts.getSizeBytes() / (1024 * 1024) : 0L)
            .sum();

        // Calculate current average query time (from slow queries or defaults)
        long currentAvgQueryTimeMs = estimateCurrentAvgQueryTime(connectionId);

        // Predict future state
        double multiplier = scenario.getMultiplier();
        long predictedTotalRows = (long) (currentTotalRows * multiplier);
        long predictedTotalSizeMb = (long) (currentTotalSizeMb * multiplier);

        // Query time grows non-linearly (assume O(n log n) for indexed queries)
        long predictedAvgQueryTimeMs = (long) (currentAvgQueryTimeMs * multiplier * Math.log(multiplier + 1));

        // Create per-table predictions
        List<TableGrowthPrediction> tablePredictions = new ArrayList<>();
        for (TableStatsHistory stats : currentStats) {
            TableGrowthPrediction prediction = predictTableGrowth(connectionId, stats, multiplier);
            tablePredictions.add(prediction);
        }

        // Assess overall risks
        Map<String, Object> risks = assessRisks(tablePredictions, scenario);
        ScalabilitySimulation.RiskLevel overallRisk = determineOverallRisk(risks);
        BigDecimal scalabilityScore = calculateScalabilityScore(risks, scenario);

        // Generate recommendations
        Map<String, Object> recommendations = generateRecommendations(tablePredictions, risks, scenario);

        // Build simulation assumptions
        Map<String, Object> assumptions = buildAssumptions(currentStats, scenario);

        // Create simulation record
        ScalabilitySimulation simulation = ScalabilitySimulation.builder()
            .connectionId(connectionId)
            .currentTotalRows(currentTotalRows)
            .currentTotalSizeMb(currentTotalSizeMb)
            .currentAvgQueryTimeMs(currentAvgQueryTimeMs)
            .growthScenario(scenario.getLabel())
            .predictedTotalRows(predictedTotalRows)
            .predictedTotalSizeMb(predictedTotalSizeMb)
            .predictedAvgQueryTimeMs(predictedAvgQueryTimeMs)
            .overallRiskLevel(overallRisk)
            .scalabilityScore(scalabilityScore)
            .risksIdentified(risks)
            .recommendations(recommendations)
            .simulationAssumptions(assumptions)
            .simulatedTables(currentStats.size())
            .simulatedAt(LocalDateTime.now())
            .build();

        simulation = simulationRepository.save(simulation);

        // Save table predictions
        for (TableGrowthPrediction prediction : tablePredictions) {
            prediction.setScalabilitySimulationId(simulation.getId());
            predictionRepository.save(prediction);
        }

        log.info("Simulation complete: {} scenario, risk={}, score={}",
            scenario.getLabel(), overallRisk, scalabilityScore);

        return simulation;
    }

    /**
     * Predict growth for a single table.
     */
    private TableGrowthPrediction predictTableGrowth(String connectionId, TableStatsHistory stats, double multiplier) {
        long currentRows = stats.getRowCount() != null ? stats.getRowCount() : 0L;
        long currentSizeMb = stats.getSizeBytes() != null ? stats.getSizeBytes() / (1024 * 1024) : 0L;
        int indexCount = 0;  // Default, as TableStatsHistory doesn't have index count

        long predictedRows = (long) (currentRows * multiplier);
        long predictedSizeMb = (long) (currentSizeMb * multiplier);

        // Estimate full scan time (assume 50MB/s read speed)
        long predictedFullScanTimeMs = (predictedSizeMb * 1000L) / 50L;

        // Detect risk factors
        boolean lacksPartitioning = shouldBePartitioned(predictedRows, predictedSizeMb);
        boolean missingCriticalIndexes = hasMissingIndexes(connectionId, stats.getTableName());
        int inefficientQueries = countInefficientQueries(connectionId, stats.getTableName());

        // Determine priority
        TableGrowthPrediction.Priority priority = determinePriority(
            predictedRows, lacksPartitioning, missingCriticalIndexes, inefficientQueries);

        // Generate recommendations
        String recommendations = generateTableRecommendations(
            stats.getTableName(), predictedRows, predictedSizeMb,
            lacksPartitioning, missingCriticalIndexes, inefficientQueries);

        return TableGrowthPrediction.builder()
            .connectionId(connectionId)
            .tableName(stats.getTableName())
            .currentRows(currentRows)
            .currentSizeMb(currentSizeMb)
            .currentIndexCount(indexCount)
            .predictedRows(predictedRows)
            .predictedSizeMb(predictedSizeMb)
            .predictedFullScanTimeMs(predictedFullScanTimeMs)
            .lacksPartitioning(lacksPartitioning)
            .missingCriticalIndexes(missingCriticalIndexes)
            .inefficientQueries(inefficientQueries)
            .recommendedActions(recommendations)
            .priority(priority)
            .build();
    }

    /**
     * Assess overall scalability risks.
     */
    private Map<String, Object> assessRisks(List<TableGrowthPrediction> predictions,
                                           ScalabilitySimulation.GrowthScenario scenario) {
        Map<String, Object> risks = new HashMap<>();
        List<Map<String, Object>> riskList = new ArrayList<>();

        // Risk 1: Large tables without partitioning
        long tablesNeedingPartitioning = predictions.stream()
            .filter(TableGrowthPrediction::getLacksPartitioning)
            .count();
        if (tablesNeedingPartitioning > 0) {
            Map<String, Object> risk = new HashMap<>();
            risk.put("type", "MISSING_PARTITIONING");
            risk.put("severity", tablesNeedingPartitioning > 5 ? "CRITICAL" : "HIGH");
            risk.put("count", tablesNeedingPartitioning);
            risk.put("description", String.format("%d table(s) will exceed 10M rows but lack partitioning",
                tablesNeedingPartitioning));
            riskList.add(risk);
        }

        // Risk 2: Missing critical indexes
        long tablesMissingIndexes = predictions.stream()
            .filter(TableGrowthPrediction::getMissingCriticalIndexes)
            .count();
        if (tablesMissingIndexes > 0) {
            Map<String, Object> risk = new HashMap<>();
            risk.put("type", "MISSING_INDEXES");
            risk.put("severity", "HIGH");
            risk.put("count", tablesMissingIndexes);
            risk.put("description", String.format("%d table(s) have missing critical indexes",
                tablesMissingIndexes));
            riskList.add(risk);
        }

        // Risk 3: Query performance degradation
        long totalInefficient = predictions.stream()
            .mapToInt(TableGrowthPrediction::getInefficientQueries)
            .sum();
        if (totalInefficient > 10) {
            Map<String, Object> risk = new HashMap<>();
            risk.put("type", "QUERY_DEGRADATION");
            risk.put("severity", totalInefficient > 50 ? "CRITICAL" : "MEDIUM");
            risk.put("count", totalInefficient);
            risk.put("description", String.format("%d inefficient queries will degrade significantly",
                totalInefficient));
            riskList.add(risk);
        }

        // Risk 4: Storage capacity
        long totalPredictedSizeMb = predictions.stream()
            .mapToLong(TableGrowthPrediction::getPredictedSizeMb)
            .sum();
        if (totalPredictedSizeMb > 500_000) {  // > 500GB
            Map<String, Object> risk = new HashMap<>();
            risk.put("type", "STORAGE_CAPACITY");
            risk.put("severity", totalPredictedSizeMb > 1_000_000 ? "HIGH" : "MEDIUM");
            risk.put("sizeMb", totalPredictedSizeMb);
            risk.put("description", String.format("Storage will grow to %.1f GB",
                totalPredictedSizeMb / 1024.0));
            riskList.add(risk);
        }

        risks.put("risks", riskList);
        risks.put("totalRisks", riskList.size());
        return risks;
    }

    /**
     * Determine overall risk level.
     */
    private ScalabilitySimulation.RiskLevel determineOverallRisk(Map<String, Object> risks) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> riskList = (List<Map<String, Object>>) risks.get("risks");

        if (riskList.isEmpty()) {
            return ScalabilitySimulation.RiskLevel.LOW;
        }

        long criticalCount = riskList.stream()
            .filter(r -> "CRITICAL".equals(r.get("severity")))
            .count();
        long highCount = riskList.stream()
            .filter(r -> "HIGH".equals(r.get("severity")))
            .count();

        if (criticalCount > 0) {
            return ScalabilitySimulation.RiskLevel.CRITICAL;
        } else if (highCount >= 2) {
            return ScalabilitySimulation.RiskLevel.HIGH;
        } else if (highCount == 1 || riskList.size() >= 3) {
            return ScalabilitySimulation.RiskLevel.MEDIUM;
        } else {
            return ScalabilitySimulation.RiskLevel.LOW;
        }
    }

    /**
     * Calculate scalability score (0-100).
     */
    private BigDecimal calculateScalabilityScore(Map<String, Object> risks,
                                                 ScalabilitySimulation.GrowthScenario scenario) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> riskList = (List<Map<String, Object>>) risks.get("risks");

        // Start at 100, deduct points for each risk
        int score = 100;

        for (Map<String, Object> risk : riskList) {
            String severity = (String) risk.get("severity");
            switch (severity) {
                case "CRITICAL":
                    score -= 30;
                    break;
                case "HIGH":
                    score -= 20;
                    break;
                case "MEDIUM":
                    score -= 10;
                    break;
                case "LOW":
                    score -= 5;
                    break;
            }
        }

        // Additional penalty for extreme growth scenarios
        if (scenario.getMultiplier() >= 10) {
            score -= 10;
        }

        score = Math.max(0, score);  // Ensure non-negative
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Generate actionable recommendations.
     */
    private Map<String, Object> generateRecommendations(List<TableGrowthPrediction> predictions,
                                                       Map<String, Object> risks,
                                                       ScalabilitySimulation.GrowthScenario scenario) {
        Map<String, Object> recommendations = new HashMap<>();
        List<Map<String, Object>> actionList = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> riskList = (List<Map<String, Object>>) risks.get("risks");

        // Generate recommendations based on risks
        for (Map<String, Object> risk : riskList) {
            String riskType = (String) risk.get("type");

            if ("MISSING_PARTITIONING".equals(riskType)) {
                List<String> tables = predictions.stream()
                    .filter(TableGrowthPrediction::getLacksPartitioning)
                    .map(TableGrowthPrediction::getTableName)
                    .limit(5)
                    .collect(Collectors.toList());

                Map<String, Object> action = new HashMap<>();
                action.put("priority", "HIGH");
                action.put("category", "PARTITIONING");
                action.put("title", "Implement table partitioning");
                action.put("description", "Large tables will benefit from range or hash partitioning");
                action.put("affectedTables", tables);
                action.put("estimatedImpact", "50-70% query performance improvement");
                actionList.add(action);
            }

            if ("MISSING_INDEXES".equals(riskType)) {
                Map<String, Object> action = new HashMap<>();
                action.put("priority", "HIGH");
                action.put("category", "INDEXING");
                action.put("title", "Add missing indexes");
                action.put("description", "Critical columns lack indexes for efficient filtering");
                action.put("estimatedImpact", "10-100x query performance improvement");
                actionList.add(action);
            }

            if ("QUERY_DEGRADATION".equals(riskType)) {
                Map<String, Object> action = new HashMap<>();
                action.put("priority", "MEDIUM");
                action.put("category", "QUERY_OPTIMIZATION");
                action.put("title", "Optimize inefficient queries");
                action.put("description", "Review and rewrite queries with high examination ratios");
                action.put("estimatedImpact", "30-60% reduction in query execution time");
                actionList.add(action);
            }

            if ("STORAGE_CAPACITY".equals(riskType)) {
                Map<String, Object> action = new HashMap<>();
                action.put("priority", "MEDIUM");
                action.put("category", "CAPACITY_PLANNING");
                action.put("title", "Plan storage expansion");
                action.put("description", String.format("Database will grow to %d GB at %s growth",
                    (Long) risk.get("sizeMb") / 1024, scenario.getLabel()));
                action.put("estimatedImpact", "Prevent storage-related outages");
                actionList.add(action);
            }
        }

        recommendations.put("actions", actionList);
        recommendations.put("totalRecommendations", actionList.size());
        return recommendations;
    }

    /**
     * Build simulation assumptions.
     */
    private Map<String, Object> buildAssumptions(List<TableStatsHistory> stats,
                                                 ScalabilitySimulation.GrowthScenario scenario) {
        Map<String, Object> assumptions = new HashMap<>();
        assumptions.put("growthMultiplier", scenario.getMultiplier());
        assumptions.put("queryTimeComplexity", "O(n log n)");
        assumptions.put("readSpeedMbps", 50);
        assumptions.put("partitionThresholdRows", 10_000_000);
        assumptions.put("tablesAnalyzed", stats.size());
        assumptions.put("assumedLinearDataGrowth", true);
        assumptions.put("assumedConstantQueryPatterns", true);
        return assumptions;
    }

    /**
     * Helper methods.
     */
    private long estimateCurrentAvgQueryTime(String connectionId) {
        // Default to 100ms if no data available
        return 100L;
    }

    private boolean shouldBePartitioned(long predictedRows, long predictedSizeMb) {
        return predictedRows > 10_000_000 || predictedSizeMb > 10_000;  // 10M rows or 10GB
    }

    private boolean hasMissingIndexes(String connectionId, String tableName) {
        // Check if table has anti-patterns related to missing indexes
        List<QueryAntiPattern> patterns = queryAntiPatternRepository
            .findByConnectionIdAndPatternType(connectionId, "HIGH_EXAMINATION_RATIO");

        return patterns.stream()
            .anyMatch(p -> p.getQueryText() != null && p.getQueryText().contains(tableName));
    }

    private int countInefficientQueries(String connectionId, String tableName) {
        List<QueryAntiPattern> patterns = queryAntiPatternRepository
            .findByConnectionIdAndPatternType(connectionId, "HIGH_EXAMINATION_RATIO");

        return (int) patterns.stream()
            .filter(p -> p.getQueryText() != null && p.getQueryText().contains(tableName))
            .count();
    }

    private TableGrowthPrediction.Priority determinePriority(long predictedRows, boolean lacksPartitioning,
                                                            boolean missingIndexes, int inefficientQueries) {
        if (predictedRows > 50_000_000 && (lacksPartitioning || missingIndexes)) {
            return TableGrowthPrediction.Priority.CRITICAL;
        } else if (predictedRows > 10_000_000 || missingIndexes || inefficientQueries > 5) {
            return TableGrowthPrediction.Priority.HIGH;
        } else if (predictedRows > 1_000_000 || inefficientQueries > 0) {
            return TableGrowthPrediction.Priority.MEDIUM;
        } else {
            return TableGrowthPrediction.Priority.LOW;
        }
    }

    private String generateTableRecommendations(String tableName, long predictedRows, long predictedSizeMb,
                                               boolean lacksPartitioning, boolean missingIndexes,
                                               int inefficientQueries) {
        List<String> recommendations = new ArrayList<>();

        if (lacksPartitioning) {
            recommendations.add(String.format(
                "Implement partitioning: Table will grow to %,d rows (%.1f GB). " +
                "Consider range partitioning by date or hash partitioning by ID.",
                predictedRows, predictedSizeMb / 1024.0));
        }

        if (missingIndexes) {
            recommendations.add("Add missing indexes on frequently filtered columns to improve query performance.");
        }

        if (inefficientQueries > 0) {
            recommendations.add(String.format(
                "Optimize %d inefficient queries accessing this table. Review WHERE clauses and add selective indexes.",
                inefficientQueries));
        }

        if (predictedSizeMb > 50_000) {  // > 50GB
            recommendations.add("Consider archiving old data or implementing data retention policies.");
        }

        return String.join(" ", recommendations);
    }

    private ScalabilitySimulation createEmptySimulation(String connectionId,
                                                       ScalabilitySimulation.GrowthScenario scenario) {
        return ScalabilitySimulation.builder()
            .connectionId(connectionId)
            .growthScenario(scenario.getLabel())
            .overallRiskLevel(ScalabilitySimulation.RiskLevel.LOW)
            .scalabilityScore(BigDecimal.valueOf(100))
            .risksIdentified(Map.of("risks", List.of()))
            .recommendations(Map.of("actions", List.of()))
            .simulationAssumptions(Map.of())
            .simulatedTables(0)
            .build();
    }

    /**
     * Get latest simulation for a connection.
     */
    public Optional<ScalabilitySimulation> getLatestSimulation(String connectionId) {
        return simulationRepository.findFirstByConnectionIdOrderBySimulatedAtDesc(connectionId);
    }

    /**
     * Get all simulations for a connection.
     */
    public List<ScalabilitySimulation> getAllSimulations(String connectionId) {
        return simulationRepository.findByConnectionIdOrderBySimulatedAtDesc(connectionId);
    }

    /**
     * Get table predictions for a simulation.
     */
    public List<TableGrowthPrediction> getTablePredictions(String simulationId) {
        return predictionRepository.findByScalabilitySimulationId(simulationId);
    }

    /**
     * Get high-risk tables.
     */
    public List<TableGrowthPrediction> getHighRiskTables(String connectionId) {
        return predictionRepository.findHighRiskTablesByConnectionId(connectionId);
    }
}
