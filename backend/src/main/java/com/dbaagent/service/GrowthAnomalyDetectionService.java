package com.dbaagent.service;

import com.dbaagent.model.CapacityForecast;
import com.dbaagent.model.GrowthAlertConfiguration;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.GrowthAlertConfigurationRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Growth Anomaly Detection Service
 * Implements 4 detection algorithms:
 * 1. Percentage Growth
 * 2. Absolute Size Growth
 * 3. Statistical Anomaly (Z-score)
 * 4. Row Count Spike
 *
 * Enhanced with Sentinel-DBA analytics:
 * - Confidence scoring
 * - Growth pattern classification
 * - Event correlation for root cause attribution
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GrowthAnomalyDetectionService {

    private final GrowthAnomalyRepository anomalyRepository;
    private final GrowthAlertConfigurationRepository configRepository;
    private final TableStatsHistoryRepository historyRepository;
    private final NotificationService notificationService;

    // Sentinel-DBA analytics services
    private final SentinelAnalyticsService sentinelAnalytics;
    private final EventCorrelationService eventCorrelation;

    /**
     * Detect anomalies by running all 4 algorithms
     * @return true if any anomaly was detected
     */
    public boolean detectAnomalies(TableStatsHistory current, TableStatsHistory previous) {
        // Get configuration (table-specific or global)
        Optional<GrowthAlertConfiguration> configOpt = configRepository
                .findEffectiveConfiguration(current.getConnectionId(), current.getTableName());

        if (configOpt.isEmpty() || !configOpt.get().getIsEnabled()) {
            log.debug("Monitoring disabled for table: {}", current.getTableName());
            return false;
        }

        GrowthAlertConfiguration config = configOpt.get();
        List<GrowthAnomaly> anomalies = new ArrayList<>();

        // Run all 4 detection algorithms
        detectPercentageGrowth(current, previous, config).ifPresent(anomalies::add);
        detectAbsoluteGrowth(current, config).ifPresent(anomalies::add);
        detectStatisticalAnomaly(current, config).ifPresent(anomalies::add);
        detectRowCountSpike(current, config).ifPresent(anomalies::add);

        // Save anomalies and enhance with Sentinel analytics
        for (GrowthAnomaly anomaly : anomalies) {
            // === SENTINEL-DBA ENHANCEMENTS ===

            try {
                // 1. Classify growth pattern (LINEAR, EXPONENTIAL, STEP_FUNCTION, etc.)
                CapacityForecast.GrowthPattern pattern = sentinelAnalytics.classifyGrowthPattern(
                        anomaly.getConnectionId(),
                        anomaly.getTableName()
                );
                anomaly.setGrowthPattern(GrowthAnomaly.GrowthPattern.valueOf(pattern.name()));

                log.debug("Classified growth pattern as {} for table {}", pattern, anomaly.getTableName());

            } catch (Exception e) {
                log.warn("Failed to classify growth pattern for table {}: {}",
                        anomaly.getTableName(), e.getMessage());
                // Continue without pattern classification
            }

            try {
                // 2. Calculate confidence score based on velocity/acceleration analysis
                Map<String, Double> velocityData = sentinelAnalytics.calculateVelocityAndAcceleration(
                        anomaly.getConnectionId(),
                        anomaly.getTableName(),
                        30  // 30 days historical window
                );

                int confidenceScore = calculateConfidenceScore(
                        anomaly,
                        velocityData.get("velocity"),
                        velocityData.get("acceleration")
                );
                anomaly.setConfidenceScore(confidenceScore);

                log.debug("Calculated confidence score {} for anomaly {} (velocity: {}, acceleration: {})",
                        confidenceScore, anomaly.getAnomalyType(),
                        String.format("%.2f", velocityData.get("velocity")),
                        String.format("%.2f", velocityData.get("acceleration")));

            } catch (Exception e) {
                log.warn("Failed to calculate confidence score for table {}: {}",
                        anomaly.getTableName(), e.getMessage());
                // Set default confidence score
                anomaly.setConfidenceScore(5);  // Medium confidence
            }

            // Save anomaly with Sentinel enhancements
            GrowthAnomaly saved = anomalyRepository.save(anomaly);

            log.warn("Growth anomaly detected: {} for table {} (Severity: {}, Confidence: {}/10, Pattern: {})",
                    anomaly.getAnomalyType(),
                    anomaly.getTableName(),
                    anomaly.getSeverity(),
                    anomaly.getConfidenceScore(),
                    anomaly.getGrowthPattern());

            try {
                // 3. Correlate with database events (deployments, schema changes, etc.)
                eventCorrelation.correlateAnomalyWithEvents(saved);

                if (saved.getAttributedEventId() != null) {
                    log.info("Anomaly {} attributed to event: {}",
                            saved.getId(), saved.getAttributedEventDescription());
                }

            } catch (Exception e) {
                log.warn("Failed to correlate events for anomaly {}: {}",
                        saved.getId(), e.getMessage());
            }

            // Send notifications
            try {
                notificationService.sendAlert(saved, config);
            } catch (Exception e) {
                log.error("Failed to send notification for anomaly: {}", saved.getId(), e);
            }
        }

        return !anomalies.isEmpty();
    }

    /**
     * Create a NEW_TABLE anomaly when a table is first detected in the growth pipeline.
     * This is a lightweight path — no comparison or Sentinel analytics needed yet.
     */
    public void createNewTableAnomaly(TableStatsHistory snapshot) {
        GrowthAnomaly anomaly = GrowthAnomaly.builder()
                .snapshotId(snapshot.getId())
                .connectionId(snapshot.getConnectionId())
                .tableName(snapshot.getTableName())
                .anomalyType(GrowthAnomaly.AnomalyType.NEW_TABLE)
                .severity(GrowthAnomaly.Severity.INFO)
                .currentSizeBytes(snapshot.getSizeBytes())
                .currentRowCount(snapshot.getRowCount())
                .confidenceScore(8)
                .build();

        anomalyRepository.save(anomaly);
        log.info("NEW_TABLE anomaly recorded for table: {} (size: {} bytes, rows: {})",
                snapshot.getTableName(), snapshot.getSizeBytes(), snapshot.getRowCount());
    }

    /**
     * Algorithm 1: Percentage Growth Detection
     * Detects when table size grows by a percentage threshold
     */
    private Optional<GrowthAnomaly> detectPercentageGrowth(
            TableStatsHistory current,
            TableStatsHistory previous,
            GrowthAlertConfiguration config) {

        if (current.getSizeGrowthPercent() == null || current.getSizeGrowthPercent() <= 0) {
            return Optional.empty(); // Ignore shrinkage or no growth
        }

        double growthPercent = current.getSizeGrowthPercent();
        GrowthAnomaly.Severity severity = null;

        if (growthPercent >= config.getPercentageGrowthCritical()) {
            severity = GrowthAnomaly.Severity.CRITICAL;
        } else if (growthPercent >= config.getPercentageGrowthWarning()) {
            severity = GrowthAnomaly.Severity.WARNING;
        }

        if (severity != null) {
            return Optional.of(GrowthAnomaly.builder()
                    .snapshotId(current.getId())
                    .connectionId(current.getConnectionId())
                    .tableName(current.getTableName())
                    .anomalyType(GrowthAnomaly.AnomalyType.PERCENTAGE_GROWTH)
                    .severity(severity)
                    .currentSizeBytes(current.getSizeBytes())
                    .previousSizeBytes(previous.getSizeBytes())
                    .sizeGrowthBytes(current.getSizeGrowthBytes())
                    .sizeGrowthPercent(growthPercent)
                    .thresholdValue(severity == GrowthAnomaly.Severity.CRITICAL ?
                            config.getPercentageGrowthCritical() : config.getPercentageGrowthWarning())
                    .build());
        }

        return Optional.empty();
    }

    /**
     * Algorithm 2: Absolute Size Growth Detection
     * Detects when table size grows by an absolute amount
     */
    private Optional<GrowthAnomaly> detectAbsoluteGrowth(
            TableStatsHistory current,
            GrowthAlertConfiguration config) {

        if (current.getSizeGrowthBytes() == null || current.getSizeGrowthBytes() <= 0) {
            return Optional.empty();
        }

        long growthBytes = current.getSizeGrowthBytes();
        GrowthAnomaly.Severity severity = null;

        if (growthBytes >= config.getAbsoluteGrowthCriticalBytes()) {
            severity = GrowthAnomaly.Severity.CRITICAL;
        } else if (growthBytes >= config.getAbsoluteGrowthWarningBytes()) {
            severity = GrowthAnomaly.Severity.WARNING;
        }

        if (severity != null) {
            return Optional.of(GrowthAnomaly.builder()
                    .snapshotId(current.getId())
                    .connectionId(current.getConnectionId())
                    .tableName(current.getTableName())
                    .anomalyType(GrowthAnomaly.AnomalyType.ABSOLUTE_GROWTH)
                    .severity(severity)
                    .currentSizeBytes(current.getSizeBytes())
                    .previousSizeBytes(current.getSizeBytes() - growthBytes)
                    .sizeGrowthBytes(growthBytes)
                    .sizeGrowthPercent(current.getSizeGrowthPercent())
                    .thresholdValue(severity == GrowthAnomaly.Severity.CRITICAL ?
                            config.getAbsoluteGrowthCriticalBytes().doubleValue() :
                            config.getAbsoluteGrowthWarningBytes().doubleValue())
                    .build());
        }

        return Optional.empty();
    }

    /**
     * Algorithm 3: Statistical Anomaly Detection (Z-score)
     * Detects when growth rate deviates significantly from historical pattern
     */
    private Optional<GrowthAnomaly> detectStatisticalAnomaly(
            TableStatsHistory current,
            GrowthAlertConfiguration config) {

        if (current.getSizeGrowthPercent() == null) {
            return Optional.empty();
        }

        // Get historical data for statistical analysis
        LocalDateTime since = current.getSnapshotTimestamp()
                .minusHours(config.getHistoricalWindowHours());

        List<TableStatsHistory> history = historyRepository.findHistoricalWindow(
                current.getConnectionId(),
                current.getTableName(),
                since
        );

        // Need minimum 10 snapshots for reliable statistics
        if (history.size() < 10) {
            log.debug("Insufficient historical data for Z-score calculation: {} snapshots (need 10)",
                    history.size());
            return Optional.empty();
        }

        // Calculate mean and standard deviation of growth rates
        List<Double> growthRates = history.stream()
                .map(TableStatsHistory::getSizeGrowthPercent)
                .filter(Objects::nonNull)
                .filter(rate -> rate != 0.0) // Filter out zero growth
                .toList();

        if (growthRates.size() < 10) {
            return Optional.empty();
        }

        double mean = growthRates.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        double variance = growthRates.stream()
                .mapToDouble(rate -> Math.pow(rate - mean, 2))
                .average()
                .orElse(0.0);

        double stdDev = Math.sqrt(variance);

        // Avoid division by zero
        if (stdDev < 0.01) {
            return Optional.empty();
        }

        // Calculate Z-score
        double zScore = (current.getSizeGrowthPercent() - mean) / stdDev;
        current.setSizeZScore(zScore);

        // Check threshold (default 3.0 = 99.7% confidence interval)
        if (Math.abs(zScore) >= config.getZScoreThreshold()) {
            // Higher Z-score = more severe
            GrowthAnomaly.Severity severity = Math.abs(zScore) >= 4.0 ?
                    GrowthAnomaly.Severity.CRITICAL : GrowthAnomaly.Severity.WARNING;

            return Optional.of(GrowthAnomaly.builder()
                    .snapshotId(current.getId())
                    .connectionId(current.getConnectionId())
                    .tableName(current.getTableName())
                    .anomalyType(GrowthAnomaly.AnomalyType.STATISTICAL_ANOMALY)
                    .severity(severity)
                    .currentSizeBytes(current.getSizeBytes())
                    .sizeGrowthPercent(current.getSizeGrowthPercent())
                    .zScore(zScore)
                    .thresholdValue(config.getZScoreThreshold())
                    .build());
        }

        return Optional.empty();
    }

    /**
     * Algorithm 4: Row Count Spike Detection
     * Detects abnormal increases in row count
     */
    private Optional<GrowthAnomaly> detectRowCountSpike(
            TableStatsHistory current,
            GrowthAlertConfiguration config) {

        if (current.getRowCountGrowth() == null || current.getRowCountGrowth() <= 0) {
            return Optional.empty();
        }

        long rowGrowth = current.getRowCountGrowth();
        double rowGrowthPercent = current.getRowCountGrowthPercent() != null ?
                current.getRowCountGrowthPercent() : 0.0;

        GrowthAnomaly.Severity severity = null;
        Double threshold = null;

        // Check absolute row count thresholds
        if (rowGrowth >= config.getRowSpikeCritical()) {
            severity = GrowthAnomaly.Severity.CRITICAL;
            threshold = config.getRowSpikeCritical().doubleValue();
        } else if (rowGrowth >= config.getRowSpikeWarning()) {
            severity = GrowthAnomaly.Severity.WARNING;
            threshold = config.getRowSpikeWarning().doubleValue();
        }
        // Also check percentage threshold
        else if (rowGrowthPercent >= config.getRowSpikePercentage()) {
            severity = GrowthAnomaly.Severity.WARNING;
            threshold = config.getRowSpikePercentage();
        }

        if (severity != null) {
            return Optional.of(GrowthAnomaly.builder()
                    .snapshotId(current.getId())
                    .connectionId(current.getConnectionId())
                    .tableName(current.getTableName())
                    .anomalyType(GrowthAnomaly.AnomalyType.ROW_SPIKE)
                    .severity(severity)
                    .currentRowCount(current.getRowCount())
                    .previousRowCount(current.getRowCount() - rowGrowth)
                    .rowCountGrowth(rowGrowth)
                    .thresholdValue(threshold)
                    .build());
        }

        return Optional.empty();
    }

    /**
     * Calculate confidence score (1-10) for an anomaly based on:
     * 1. Anomaly severity (CRITICAL = higher confidence)
     * 2. Growth velocity (higher velocity = higher confidence)
     * 3. Acceleration (high acceleration = lower confidence due to instability)
     */
    private int calculateConfidenceScore(GrowthAnomaly anomaly, Double velocity, Double acceleration) {
        int score = 5;  // Base score

        // 1. Severity impact (+2 for CRITICAL)
        if (anomaly.getSeverity() == GrowthAnomaly.Severity.CRITICAL) {
            score += 2;
        } else if (anomaly.getSeverity() == GrowthAnomaly.Severity.WARNING) {
            score += 1;
        }

        // 2. Velocity impact (+2 max)
        if (velocity != null) {
            if (Math.abs(velocity) > 10.0) {  // High velocity (>10%/day)
                score += 2;
            } else if (Math.abs(velocity) > 5.0) {  // Moderate velocity
                score += 1;
            }
        }

        // 3. Acceleration penalty (-2 max)
        if (acceleration != null) {
            if (Math.abs(acceleration) > 5.0) {  // High acceleration = unstable
                score -= 2;
            } else if (Math.abs(acceleration) > 2.0) {  // Moderate acceleration
                score -= 1;
            }
        }

        // 4. Anomaly type reliability adjustments
        switch (anomaly.getAnomalyType()) {
            case PERCENTAGE_GROWTH:
            case ABSOLUTE_GROWTH:
                // Direct measurements = higher confidence
                score += 1;
                break;
            case STATISTICAL_ANOMALY:
                // Z-score based = reliable if Z-score is high
                if (anomaly.getZScore() != null && Math.abs(anomaly.getZScore()) > 4.0) {
                    score += 1;
                }
                break;
            case ROW_SPIKE:
                // Row spikes can be temporary, slightly less reliable
                break;
            case NEW_TABLE:
                // New table detection is always reliable
                score += 1;
                break;
        }

        // Clamp between 1 and 10
        return Math.max(1, Math.min(10, score));
    }
}
