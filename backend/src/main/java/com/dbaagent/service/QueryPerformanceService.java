package com.dbaagent.service;

import com.dbaagent.model.QueryPerformanceBaseline;
import com.dbaagent.model.QueryPerformanceHistory;
import com.dbaagent.model.QueryPerformanceRegression;
import com.dbaagent.repository.QueryPerformanceBaselineRepository;
import com.dbaagent.repository.QueryPerformanceHistoryRepository;
import com.dbaagent.repository.QueryPerformanceRegressionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.dbaagent.util.QueryNormalizer;

@Service
@Slf4j
public class QueryPerformanceService {

    @Autowired
    private QueryPerformanceHistoryRepository historyRepository;

    @Autowired
    private QueryPerformanceBaselineRepository baselineRepository;

    @Autowired
    private QueryPerformanceRegressionRepository regressionRepository;

    /**
     * Record a query execution for performance tracking
     */
    @Transactional
    public QueryPerformanceHistory recordQueryExecution(
            String connectionId,
            String queryText,
            Double executionTimeMs,
            Long rowsExamined,
            Long rowsSent,
            String databaseName
    ) {
        try {
            String normalizedQuery = QueryNormalizer.normalize(queryText);
            String queryHash = QueryNormalizer.generateMD5Hash(
                normalizedQuery.isBlank() ? String.valueOf(queryText) : normalizedQuery
            );

            QueryPerformanceHistory history = QueryPerformanceHistory.builder()
                    .connectionId(connectionId)
                    .queryHash(queryHash)
                    .queryText(queryText)
                    .normalizedQuery(normalizedQuery)
                    .executionTimeMs(executionTimeMs)
                    .rowsExamined(rowsExamined)
                    .rowsSent(rowsSent)
                    .databaseName(databaseName)
                    .build();

            QueryPerformanceHistory saved = historyRepository.save(history);
            log.debug("Recorded query execution: {} ({}ms)", queryHash, executionTimeMs);

            return saved;
        } catch (Exception e) {
            log.error("Error recording query execution", e);
            throw e;
        }
    }

    /**
     * Get performance history for a specific query
     */
    public List<QueryPerformanceHistory> getQueryHistory(String connectionId, String queryHash, int days) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days);
        LocalDateTime endTime = LocalDateTime.now();
        return historyRepository.findByConnectionAndQueryInTimeRange(connectionId, queryHash, startTime, endTime);
    }

    /**
     * Get all tracked queries for a connection
     */
    public List<Map<String, Object>> getTrackedQueries(String connectionId) {
        List<String> queryHashes = historyRepository.findDistinctQueryHashesByConnectionId(connectionId);

        return queryHashes.stream().map(queryHash -> {
            Optional<QueryPerformanceBaseline> baseline = baselineRepository.findByConnectionIdAndQueryHash(
                    connectionId, queryHash);

            List<QueryPerformanceHistory> recentHistory = historyRepository
                    .findByConnectionIdAndQueryHashOrderByExecutionTimestampDesc(connectionId, queryHash)
                    .stream()
                    .limit(100)
                    .collect(Collectors.toList());

            if (recentHistory.isEmpty()) {
                return null;
            }

            QueryPerformanceHistory latest = recentHistory.get(0);
            double recentAvg = recentHistory.stream()
                    .limit(10)
                    .mapToDouble(QueryPerformanceHistory::getExecutionTimeMs)
                    .average()
                    .orElse(0.0);

            Map<String, Object> queryInfo = new HashMap<>();
            queryInfo.put("queryHash", queryHash);
            queryInfo.put("normalizedQuery", latest.getNormalizedQuery());
            queryInfo.put("latestExecutionTime", latest.getExecutionTimeMs());
            queryInfo.put("recentAvgExecutionTime", recentAvg);
            queryInfo.put("lastSeen", latest.getExecutionTimestamp());
            queryInfo.put("executionCount", recentHistory.size());

            baseline.ifPresent(b -> {
                queryInfo.put("baselineAvg", b.getAvgExecutionTimeMs());
                queryInfo.put("p95", b.getP95ExecutionTimeMs());
                queryInfo.put("p99", b.getP99ExecutionTimeMs());

                // Calculate performance change
                double percentChange = ((recentAvg - b.getAvgExecutionTimeMs()) / b.getAvgExecutionTimeMs()) * 100;
                queryInfo.put("performanceChange", percentChange);
            });

            // Check for active regressions
            List<QueryPerformanceRegression> activeRegressions = regressionRepository
                    .findActiveRegressionsByQuery(connectionId, queryHash);
            queryInfo.put("hasActiveRegression", !activeRegressions.isEmpty());
            if (!activeRegressions.isEmpty()) {
                queryInfo.put("regressionSeverity", activeRegressions.get(0).getSeverity());
            }

            return queryInfo;
        }).filter(Objects::nonNull)
                .sorted((a, b) -> {
                    LocalDateTime timeA = (LocalDateTime) a.get("lastSeen");
                    LocalDateTime timeB = (LocalDateTime) b.get("lastSeen");
                    return timeB.compareTo(timeA);
                })
                .collect(Collectors.toList());
    }

    /**
     * Get performance trend data for charting
     */
    public Map<String, Object> getPerformanceTrend(String connectionId, String queryHash, int days) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days);
        LocalDateTime endTime = LocalDateTime.now();

        List<QueryPerformanceHistory> history = historyRepository.findByConnectionAndQueryInTimeRange(
                connectionId, queryHash, startTime, endTime);

        if (history.isEmpty()) {
            return Collections.emptyMap();
        }

        // Group by hour for trend analysis
        Map<String, List<QueryPerformanceHistory>> groupedByHour = history.stream()
                .collect(Collectors.groupingBy(h ->
                        h.getExecutionTimestamp().toLocalDate().toString() + " " +
                                h.getExecutionTimestamp().getHour() + ":00"
                ));

        List<Map<String, Object>> trendData = groupedByHour.entrySet().stream()
                .map(entry -> {
                    List<QueryPerformanceHistory> hourData = entry.getValue();
                    double avgTime = hourData.stream()
                            .mapToDouble(QueryPerformanceHistory::getExecutionTimeMs)
                            .average()
                            .orElse(0.0);
                    double maxTime = hourData.stream()
                            .mapToDouble(QueryPerformanceHistory::getExecutionTimeMs)
                            .max()
                            .orElse(0.0);
                    double minTime = hourData.stream()
                            .mapToDouble(QueryPerformanceHistory::getExecutionTimeMs)
                            .min()
                            .orElse(0.0);

                    Map<String, Object> dataPoint = new HashMap<>();
                    dataPoint.put("timestamp", hourData.get(0).getExecutionTimestamp());
                    dataPoint.put("avgExecutionTime", avgTime);
                    dataPoint.put("maxExecutionTime", maxTime);
                    dataPoint.put("minExecutionTime", minTime);
                    dataPoint.put("executionCount", hourData.size());
                    return dataPoint;
                })
                .sorted((a, b) -> ((LocalDateTime) a.get("timestamp")).compareTo((LocalDateTime) b.get("timestamp")))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("trendData", trendData);
        result.put("sampleQuery", history.get(0).getQueryText());
        result.put("totalExecutions", history.size());

        return result;
    }

    /**
     * Get all performance regressions
     */
    public List<QueryPerformanceRegression> getRegressions(String connectionId, boolean unacknowledgedOnly) {
        if (unacknowledgedOnly) {
            return regressionRepository.findByConnectionIdAndAcknowledgedFalseAndResolvedFalseOrderByDetectedAtDesc(
                    connectionId);
        }
        return regressionRepository.findByConnectionIdOrderByDetectedAtDesc(connectionId);
    }

    /**
     * The connection a regression belongs to, for authorizing an endpoint keyed
     * only on the regression id. Empty when the id does not exist.
     */
    public Optional<String> findConnectionIdForRegression(Long regressionId) {
        return regressionRepository.findById(regressionId)
                .map(QueryPerformanceRegression::getConnectionId);
    }

    /**
     * Acknowledge a regression
     */
    @Transactional
    public void acknowledgeRegression(Long regressionId, String acknowledgedBy) {
        QueryPerformanceRegression regression = regressionRepository.findById(regressionId)
                .orElseThrow(() -> new RuntimeException("Regression not found"));

        regression.setAcknowledged(true);
        regression.setAcknowledgedBy(acknowledgedBy);
        regression.setAcknowledgedAt(LocalDateTime.now());

        regressionRepository.save(regression);
        log.info("Regression {} acknowledged by {}", regressionId, acknowledgedBy);
    }

    /**
     * Mark a regression as resolved
     */
    @Transactional
    public void resolveRegression(Long regressionId, String resolutionNotes) {
        QueryPerformanceRegression regression = regressionRepository.findById(regressionId)
                .orElseThrow(() -> new RuntimeException("Regression not found"));

        regression.setResolved(true);
        regression.setResolvedAt(LocalDateTime.now());
        regression.setResolutionNotes(resolutionNotes);

        regressionRepository.save(regression);
        log.info("Regression {} marked as resolved", regressionId);
    }

    /**
     * Scheduled task to calculate baselines and detect regressions
     */
    @Transactional
    public void analyzePerformance() {
        log.info("Starting scheduled performance analysis");

        try {
            List<String> connectionIds = historyRepository.findAll().stream()
                    .map(QueryPerformanceHistory::getConnectionId)
                    .distinct()
                    .collect(Collectors.toList());

            for (String connectionId : connectionIds) {
                analyzeConnectionPerformance(connectionId);
            }

            log.info("Completed scheduled performance analysis");
        } catch (Exception e) {
            log.error("Error in scheduled performance analysis", e);
        }
    }

    private void analyzeConnectionPerformance(String connectionId) {
        List<String> queryHashes = historyRepository.findDistinctQueryHashesByConnectionId(connectionId);

        for (String queryHash : queryHashes) {
            try {
                updateBaseline(connectionId, queryHash);
                detectRegressions(connectionId, queryHash);
            } catch (Exception e) {
                log.error("Error analyzing query {}: {}", queryHash, e.getMessage());
            }
        }
    }

    private void updateBaseline(String connectionId, String queryHash) {
        // Get last 7 days of data
        LocalDateTime startTime = LocalDateTime.now().minusDays(7);
        LocalDateTime endTime = LocalDateTime.now();

        List<QueryPerformanceHistory> history = historyRepository.findByConnectionAndQueryInTimeRange(
                connectionId, queryHash, startTime, endTime);

        if (history.size() < 10) {
            log.debug("Insufficient data for baseline calculation: {} samples", history.size());
            return;
        }

        List<Double> executionTimes = history.stream()
                .map(QueryPerformanceHistory::getExecutionTimeMs)
                .sorted()
                .collect(Collectors.toList());

        double avg = executionTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double min = executionTimes.get(0);
        double max = executionTimes.get(executionTimes.size() - 1);
        double p50 = percentile(executionTimes, 50);
        double p95 = percentile(executionTimes, 95);
        double p99 = percentile(executionTimes, 99);
        double stdDev = calculateStdDev(executionTimes, avg);

        QueryPerformanceBaseline baseline = baselineRepository
                .findByConnectionIdAndQueryHash(connectionId, queryHash)
                .orElse(new QueryPerformanceBaseline());

        baseline.setConnectionId(connectionId);
        baseline.setQueryHash(queryHash);
        baseline.setNormalizedQuery(history.get(0).getNormalizedQuery());
        baseline.setAvgExecutionTimeMs(avg);
        baseline.setP50ExecutionTimeMs(p50);
        baseline.setP95ExecutionTimeMs(p95);
        baseline.setP99ExecutionTimeMs(p99);
        baseline.setMinExecutionTimeMs(min);
        baseline.setMaxExecutionTimeMs(max);
        baseline.setStdDevExecutionTimeMs(stdDev);
        baseline.setSampleCount(history.size());
        baseline.setFirstSeen(history.get(history.size() - 1).getExecutionTimestamp());
        baseline.setLastSeen(history.get(0).getExecutionTimestamp());
        baseline.setBaselineCalculatedAt(LocalDateTime.now());
        baseline.setBaselineWindowDays(7);

        baselineRepository.save(baseline);
        log.debug("Updated baseline for query {}: avg={}ms", queryHash, avg);
    }

    private void detectRegressions(String connectionId, String queryHash) {
        Optional<QueryPerformanceBaseline> baselineOpt = baselineRepository
                .findByConnectionIdAndQueryHash(connectionId, queryHash);

        if (baselineOpt.isEmpty()) {
            return; // No baseline yet
        }

        QueryPerformanceBaseline baseline = baselineOpt.get();

        // Get recent performance (last hour)
        LocalDateTime startTime = LocalDateTime.now().minusHours(1);
        LocalDateTime endTime = LocalDateTime.now();

        List<QueryPerformanceHistory> recentHistory = historyRepository.findByConnectionAndQueryInTimeRange(
                connectionId, queryHash, startTime, endTime);

        if (recentHistory.size() < 3) {
            return; // Need at least 3 samples
        }

        double recentAvg = recentHistory.stream()
                .mapToDouble(QueryPerformanceHistory::getExecutionTimeMs)
                .average()
                .orElse(0.0);

        double slowdownPercent = ((recentAvg - baseline.getAvgExecutionTimeMs()) / baseline.getAvgExecutionTimeMs()) * 100;
        double slowdownFactor = recentAvg / baseline.getAvgExecutionTimeMs();

        // Detect regression if >50% slower
        if (slowdownPercent > 50.0) {
            // Check if we already have an active regression for this query
            List<QueryPerformanceRegression> existingRegressions = regressionRepository
                    .findActiveRegressionsByQuery(connectionId, queryHash);

            if (existingRegressions.isEmpty()) {
                QueryPerformanceRegression.Severity severity;
                if (slowdownPercent > 200) {
                    severity = QueryPerformanceRegression.Severity.CRITICAL;
                } else if (slowdownPercent > 100) {
                    severity = QueryPerformanceRegression.Severity.SEVERE;
                } else if (slowdownPercent > 50) {
                    severity = QueryPerformanceRegression.Severity.MODERATE;
                } else {
                    severity = QueryPerformanceRegression.Severity.MINOR;
                }

                QueryPerformanceRegression regression = QueryPerformanceRegression.builder()
                        .connectionId(connectionId)
                        .queryHash(queryHash)
                        .normalizedQuery(baseline.getNormalizedQuery())
                        .severity(severity)
                        .regressionType(QueryPerformanceRegression.RegressionType.SUDDEN_SPIKE)
                        .baselineAvgMs(baseline.getAvgExecutionTimeMs())
                        .currentAvgMs(recentAvg)
                        .slowdownFactor(slowdownFactor)
                        .slowdownPercent(slowdownPercent)
                        .detectionWindowStart(startTime)
                        .detectionWindowEnd(endTime)
                        .sampleCount(recentHistory.size())
                        .build();

                regressionRepository.save(regression);
                log.warn("Performance regression detected: query={}, slowdown={}%, severity={}",
                        queryHash, slowdownPercent, severity);
            }
        }
    }

    // Helper methods

    private double percentile(List<Double> sortedValues, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(sortedValues.size() - 1, index));
        return sortedValues.get(index);
    }

    private double calculateStdDev(List<Double> values, double mean) {
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }

    /**
     * Cleanup old data
     */
    @Transactional
    public void cleanupOldData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        historyRepository.deleteByExecutionTimestampBefore(cutoff);

        LocalDateTime regressionCutoff = LocalDateTime.now().minusDays(30);
        regressionRepository.deleteByDetectedAtBefore(regressionCutoff);

        log.info("Cleaned up old query performance data");
    }
}
