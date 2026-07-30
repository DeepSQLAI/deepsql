package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sentinel-DBA Analytics Service
 * Core predictive analytics engine implementing 6 analysis pillars:
 * 1. Velocity & Acceleration (Growth rate derivatives)
 * 2. Death Clock Forecasting (Days to exhaustion)
 * 3. Growth Pattern Classification (Linear/Exponential/Step)
 * 4. Workload Drift Detection (Performance without growth)
 * 5. Resource Headroom Analysis (Capacity utilization)
 * 6. Risk Confidence Scoring (Forecast reliability)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SentinelAnalyticsService {

    private final TableStatsHistoryRepository historyRepository;
    private final CapacityForecastRepository forecastRepository;
    private final ResourceLimitsRepository limitsRepository;
    private final WorkloadDriftMetricsRepository driftRepository;
    private final SentinelRecommendationRepository recommendationRepository;
    private final DatabaseEventRepository eventRepository;

    // ====================================================================================
    // PILLAR 1: VELOCITY & ACCELERATION (First and Second Derivatives)
    // ====================================================================================

    /**
     * Calculate growth velocity (first derivative) and acceleration (second derivative)
     * Returns: {velocity: %/day, acceleration: %/day²}
     */
    public Map<String, Double> calculateVelocityAndAcceleration(
            String connectionId, String tableName, int historicalDays) {

        LocalDateTime since = LocalDateTime.now().minusDays(historicalDays);
        List<TableStatsHistory> history = historyRepository
                .findByConnectionIdAndTableNameAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                        connectionId, tableName, since, LocalDateTime.now());

        if (history.size() < 3) {
            return Map.of("velocity", 0.0, "acceleration", 0.0, "dataPoints", (double) history.size());
        }

        // Calculate velocity (first derivative): average growth rate
        List<Double> growthRates = new ArrayList<>();
        for (int i = 1; i < history.size(); i++) {
            Double growthPercent = history.get(i).getSizeGrowthPercent();
            if (growthPercent != null && growthPercent != 0.0) {
                // Normalize to daily rate
                long hoursBetween = ChronoUnit.HOURS.between(
                        history.get(i - 1).getSnapshotTimestamp(),
                        history.get(i).getSnapshotTimestamp());
                double dailyRate = (growthPercent / hoursBetween) * 24;
                growthRates.add(dailyRate);
            }
        }

        if (growthRates.isEmpty()) {
            return Map.of("velocity", 0.0, "acceleration", 0.0);
        }

        double avgVelocity = growthRates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        // Calculate acceleration (second derivative): change in growth rate
        if (growthRates.size() < 2) {
            return Map.of("velocity", avgVelocity, "acceleration", 0.0);
        }

        // Compare recent velocity (last 25%) vs. older velocity (first 25%)
        int quarterSize = Math.max(1, growthRates.size() / 4);
        double recentVelocity = growthRates.subList(growthRates.size() - quarterSize, growthRates.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double oldVelocity = growthRates.subList(0, quarterSize)
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double acceleration = recentVelocity - oldVelocity;  // %/day change

        return Map.of(
                "velocity", avgVelocity,
                "acceleration", acceleration,
                "recentVelocity", recentVelocity,
                "oldVelocity", oldVelocity,
                "dataPoints", (double) growthRates.size()
        );
    }

    // ====================================================================================
    // PILLAR 2: DEATH CLOCK FORECASTING (Days to Resource Exhaustion)
    // ====================================================================================

    /**
     * Forecast resource exhaustion dates using linear regression
     * Returns CapacityForecast entity with Death Clock predictions
     */
    @Transactional
    public CapacityForecast forecastResourceExhaustion(String connectionId, String tableName) {
        log.info("Forecasting resource exhaustion for {}/{}", connectionId, tableName);

        // Get resource limits
        ResourceLimits limits = limitsRepository.findByConnectionId(connectionId)
                .orElse(null);

        if (limits == null) {
            log.warn("No resource limits configured for connection: {}", connectionId);
            return null;
        }

        // Get historical data (30 days for trend analysis)
        Map<String, Double> velocityData = calculateVelocityAndAcceleration(connectionId, tableName, 30);
        double dailyGrowthRate = velocityData.get("velocity");
        double acceleration = velocityData.get("acceleration");

        if (dailyGrowthRate <= 0) {
            log.debug("No growth detected for {}, skipping forecast", tableName);
            return null;  // No growth, no forecast needed
        }

        // Get current state
        TableStatsHistory latest = historyRepository
                .findFirstByConnectionIdAndTableNameOrderBySnapshotTimestampDesc(connectionId, tableName)
                .orElse(null);

        if (latest == null) {
            return null;
        }

        // Calculate current utilization percentages
        limits.setCurrentStorageBytes(latest.getSizeBytes());  // Update current usage
        Double currentStoragePercent = limits.getCurrentStoragePercent();

        if (currentStoragePercent == null) {
            return null;
        }

        // Forecast storage exhaustion
        LocalDateTime storageExhaustionDate = calculateExhaustionDate(
                currentStoragePercent, dailyGrowthRate, 100.0);

        // Classify growth pattern
        CapacityForecast.GrowthPattern pattern = classifyGrowthPattern(connectionId, tableName);

        // Calculate confidence score
        int confidenceScore = calculateConfidenceScore(velocityData.get("dataPoints").intValue(),
                acceleration, pattern);

        // Calculate risk score (1-10)
        int riskScore = calculateRiskScore(storageExhaustionDate, confidenceScore);

        // Build forecast
        CapacityForecast forecast = CapacityForecast.builder()
                .connectionId(connectionId)
                .tableName(tableName)
                .forecastDate(LocalDateTime.now())
                .currentStoragePercent(currentStoragePercent)
                .dailyStorageGrowthRate(dailyGrowthRate)
                .storageAcceleration(acceleration)
                .storageExhaustionDate(storageExhaustionDate)
                .growthPattern(pattern)
                .patternConfidence(confidenceScore)
                .riskScore(riskScore)
                .confidenceScore(confidenceScore)
                .dataPointsUsed(velocityData.get("dataPoints").intValue())
                .build();

        return forecastRepository.save(forecast);
    }

    /**
     * Calculate when a resource will reach exhaustion
     */
    private LocalDateTime calculateExhaustionDate(double currentPercent, double dailyGrowthRate, double targetPercent) {
        if (dailyGrowthRate <= 0) {
            return null;  // No growth, no exhaustion
        }

        double remainingPercent = targetPercent - currentPercent;
        if (remainingPercent <= 0) {
            return LocalDateTime.now();  // Already at/over capacity
        }

        long daysToExhaustion = Math.round(remainingPercent / dailyGrowthRate);

        if (daysToExhaustion > 3650) {  // Cap at 10 years
            return null;
        }

        return LocalDateTime.now().plusDays(daysToExhaustion);
    }

    // ====================================================================================
    // PILLAR 3: GROWTH PATTERN CLASSIFICATION
    // ====================================================================================

    /**
     * Classify growth pattern using statistical analysis
     */
    public CapacityForecast.GrowthPattern classifyGrowthPattern(String connectionId, String tableName) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<TableStatsHistory> history = historyRepository
                .findByConnectionIdAndTableNameAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                        connectionId, tableName, since, LocalDateTime.now());

        if (history.size() < 5) {
            return CapacityForecast.GrowthPattern.STABLE;
        }

        List<Double> growthRates = history.stream()
                .map(TableStatsHistory::getSizeGrowthPercent)
                .filter(Objects::nonNull)
                .filter(rate -> rate != 0.0)
                .collect(Collectors.toList());

        if (growthRates.isEmpty()) {
            return CapacityForecast.GrowthPattern.STABLE;
        }

        // Calculate statistics
        double mean = growthRates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = growthRates.stream()
                .mapToDouble(rate -> Math.pow(rate - mean, 2))
                .average().orElse(0.0);
        double stdDev = Math.sqrt(variance);
        double coefficientOfVariation = stdDev / Math.abs(mean);

        // Check for step function (sudden spike)
        double maxGrowth = growthRates.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (maxGrowth > mean * 5 && maxGrowth > 50.0) {  // Spike > 5x mean and > 50%
            return CapacityForecast.GrowthPattern.STEP_FUNCTION;
        }

        // Check for exponential (accelerating growth)
        if (growthRates.size() >= 3) {
            double firstHalfAvg = growthRates.subList(0, growthRates.size() / 2)
                    .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double secondHalfAvg = growthRates.subList(growthRates.size() / 2, growthRates.size())
                    .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

            if (secondHalfAvg > firstHalfAvg * 2) {  // Recent growth > 2x older growth
                return CapacityForecast.GrowthPattern.EXPONENTIAL;
            }
        }

        // Check for erratic (high variance)
        if (coefficientOfVariation > 1.0) {  // CV > 1 = high variability
            return CapacityForecast.GrowthPattern.ERRATIC;
        }

        // Check for declining
        if (mean < -0.1) {  // Negative growth
            return CapacityForecast.GrowthPattern.DECLINING;
        }

        // Check for stable (very low growth)
        if (Math.abs(mean) < 0.5 && stdDev < 1.0) {
            return CapacityForecast.GrowthPattern.STABLE;
        }

        // Default: Linear
        return CapacityForecast.GrowthPattern.LINEAR;
    }

    // ====================================================================================
    // PILLAR 4: WORKLOAD DRIFT DETECTION
    // ====================================================================================

    /**
     * Detect workload drift (performance degradation without proportional growth)
     */
    public boolean detectWorkloadDrift(String connectionId, String tableName) {
        WorkloadDriftMetrics latest = driftRepository
                .findFirstByConnectionIdAndTableNameOrderByMeasurementTimestampDesc(connectionId, tableName)
                .orElse(null);

        if (latest == null) {
            return false;
        }

        WorkloadDriftMetrics previous = driftRepository
                .findFirstByConnectionIdAndTableNameAndMeasurementTimestampBeforeOrderByMeasurementTimestampDesc(
                        connectionId, tableName, latest.getMeasurementTimestamp())
                .orElse(null);

        return latest.hasWorkloadDrift(previous);
    }

    // ====================================================================================
    // PILLAR 5: RESOURCE HEADROOM ANALYSIS
    // ====================================================================================

    /**
     * Analyze remaining capacity headroom across all resources
     */
    public Map<String, Object> analyzeResourceHeadroom(String connectionId) {
        ResourceLimits limits = limitsRepository.findByConnectionId(connectionId).orElse(null);

        if (limits == null) {
            return Map.of("configured", false);
        }

        Map<String, Object> headroom = new HashMap<>();
        headroom.put("configured", true);

        // Storage headroom
        Double storagePercent = limits.getCurrentStoragePercent();
        if (storagePercent != null) {
            headroom.put("storageUsedPercent", storagePercent);
            headroom.put("storageRemainingPercent", 100.0 - storagePercent);
            headroom.put("storageStatus", getResourceStatus(storagePercent,
                    limits.getStorageWarningPercent(), limits.getStorageCriticalPercent()));
        }

        // IOPS headroom
        Double iopsPercent = limits.getCurrentIopsPercent();
        if (iopsPercent != null) {
            headroom.put("iopsUsedPercent", iopsPercent);
            headroom.put("iopsRemainingPercent", 100.0 - iopsPercent);
            headroom.put("iopsStatus", getResourceStatus(iopsPercent,
                    limits.getIopsWarningPercent(), limits.getIopsCriticalPercent()));
        }

        // Connection headroom
        Double connectionPercent = limits.getCurrentConnectionPercent();
        if (connectionPercent != null) {
            headroom.put("connectionsUsedPercent", connectionPercent);
            headroom.put("connectionsRemainingPercent", 100.0 - connectionPercent);
            headroom.put("connectionsStatus", getResourceStatus(connectionPercent,
                    limits.getConnectionWarningPercent(), limits.getConnectionCriticalPercent()));
        }

        return headroom;
    }

    private String getResourceStatus(double percent, double warningThreshold, double criticalThreshold) {
        if (percent >= criticalThreshold) return "CRITICAL";
        if (percent >= warningThreshold) return "WARNING";
        return "OK";
    }

    // ====================================================================================
    // PILLAR 6: RISK CONFIDENCE SCORING
    // ====================================================================================

    /**
     * Calculate confidence score (1-10) for a forecast
     * Based on data density, variance, and pattern stability
     */
    private int calculateConfidenceScore(int dataPoints, double acceleration, CapacityForecast.GrowthPattern pattern) {
        int score = 5;  // Base score

        // Data density scoring (+3 max)
        if (dataPoints >= 30) score += 3;
        else if (dataPoints >= 20) score += 2;
        else if (dataPoints >= 10) score += 1;

        // Pattern stability scoring (+2 max)
        switch (pattern) {
            case LINEAR:
            case STABLE:
                score += 2;  // Most predictable
                break;
            case EXPONENTIAL:
            case DECLINING:
                score += 1;  // Moderately predictable
                break;
            case STEP_FUNCTION:
            case SEASONAL:
            case ERRATIC:
                score -= 1;  // Less predictable
                break;
        }

        // Acceleration penalty
        if (Math.abs(acceleration) > 5.0) {
            score -= 2;  // High acceleration = less confidence
        } else if (Math.abs(acceleration) > 2.0) {
            score -= 1;
        }

        return Math.max(1, Math.min(10, score));  // Clamp 1-10
    }

    /**
     * Calculate risk score (1-10) based on urgency and confidence
     */
    private int calculateRiskScore(LocalDateTime exhaustionDate, int confidenceScore) {
        if (exhaustionDate == null) {
            return 1;  // No risk if no exhaustion predicted
        }

        long daysToExhaustion = ChronoUnit.DAYS.between(LocalDateTime.now(), exhaustionDate);

        int urgencyScore;
        if (daysToExhaustion <= 7) urgencyScore = 10;  // Critical: < 1 week
        else if (daysToExhaustion <= 14) urgencyScore = 9;  // Very high: < 2 weeks
        else if (daysToExhaustion <= 30) urgencyScore = 7;  // High: < 1 month
        else if (daysToExhaustion <= 60) urgencyScore = 5;  // Medium: < 2 months
        else if (daysToExhaustion <= 90) urgencyScore = 3;  // Low: < 3 months
        else urgencyScore = 1;  // Very low: > 3 months

        // Adjust risk by confidence
        double confidenceFactor = confidenceScore / 10.0;
        int riskScore = (int) Math.round(urgencyScore * confidenceFactor);

        return Math.max(1, Math.min(10, riskScore));
    }

    /**
     * Generate comprehensive Sentinel-DBA analysis summary
     */
    @Transactional
    public Map<String, Object> generateSentinelSummary(String connectionId) {
        Map<String, Object> summary = new HashMap<>();

        // Get all forecasts
        List<CapacityForecast> forecasts = forecastRepository
                .findByConnectionIdOrderByForecastDateDesc(connectionId);

        // Get critical forecasts
        List<CapacityForecast> critical = forecastRepository.findCriticalForecasts(connectionId);

        // Resource headroom
        Map<String, Object> headroom = analyzeResourceHeadroom(connectionId);

        summary.put("totalForecasts", forecasts.size());
        summary.put("criticalForecasts", critical.size());
        summary.put("resourceHeadroom", headroom);

        // Find most critical (soonest exhaustion)
        CapacityForecast mostCritical = forecasts.stream()
                .filter(f -> f.getStorageExhaustionDate() != null)
                .min(Comparator.comparing(CapacityForecast::getStorageExhaustionDate))
                .orElse(null);

        if (mostCritical != null) {
            summary.put("mostCritical", Map.of(
                    "tableName", mostCritical.getTableName(),
                    "daysToExhaustion", mostCritical.getDaysToStorageExhaustion(),
                    "exhaustionDate", mostCritical.getStorageExhaustionDate(),
                    "riskScore", mostCritical.getRiskScore(),
                    "growthPattern", mostCritical.getGrowthPattern()
            ));
        }

        return summary;
    }

    // ========== API HELPER METHODS FOR CONTROLLER ==========

    /**
     * Get all forecasts for a connection
     */
    public List<CapacityForecast> getAllForecasts(String connectionId) {
        return forecastRepository.findByConnectionIdOrderByForecastDateDesc(connectionId);
    }

    /**
     * Get critical forecasts (risk >= 7, confidence >= 7)
     */
    public List<CapacityForecast> getCriticalForecasts(String connectionId) {
        return forecastRepository.findCriticalForecasts(connectionId);
    }

    /**
     * Get all recommendations for a connection
     */
    public List<SentinelRecommendation> getAllRecommendations(String connectionId) {
        return recommendationRepository.findByConnectionIdOrderByPriorityAscCreatedAtDesc(connectionId);
    }

    /**
     * Get recommendations by status
     */
    public List<SentinelRecommendation> getRecommendationsByStatus(
            String connectionId, SentinelRecommendation.Status status) {
        return recommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(connectionId, status);
    }

    /**
     * Get recommendations by priority
     */
    public List<SentinelRecommendation> getRecommendationsByPriority(
            String connectionId, SentinelRecommendation.Priority priority) {
        return recommendationRepository.findByConnectionIdAndPriorityOrderByCreatedAtDesc(connectionId, priority);
    }

    /**
     * Update recommendation status
     */
    public SentinelRecommendation updateRecommendationStatus(
            String recommendationId,
            SentinelRecommendation.Status status,
            String updatedBy) {

        SentinelRecommendation recommendation = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new IllegalArgumentException("Recommendation not found: " + recommendationId));

        recommendation.setStatus(status);
        recommendation.setUpdatedAt(LocalDateTime.now());

        if (status == SentinelRecommendation.Status.COMPLETED) {
            recommendation.setImplementedAt(LocalDateTime.now());
            recommendation.setImplementedBy(updatedBy);
        }

        return recommendationRepository.save(recommendation);
    }

    /**
     * Get recent database events
     */
    public List<DatabaseEvent> getRecentEvents(String connectionId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return eventRepository.findRecentEvents(connectionId, since);
    }
}
