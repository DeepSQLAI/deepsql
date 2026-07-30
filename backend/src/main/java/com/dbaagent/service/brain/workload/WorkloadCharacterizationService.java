package com.dbaagent.service.brain.workload;

import com.dbaagent.model.brain.WorkloadMetricsSnapshot;
import com.dbaagent.model.brain.WorkloadProfile;
import com.dbaagent.repository.brain.WorkloadMetricsSnapshotRepository;
import com.dbaagent.repository.brain.WorkloadProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Brain 2.0: Workload Characterization Service
 *
 * Analyzes collected metrics to characterize workload patterns.
 * Implements factor analysis and k-means clustering to reduce
 * high-dimensional metrics to representative features.
 *
 * Inspired by OtterTune's workload characterization component.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadCharacterizationService {

    private final WorkloadMetricsSnapshotRepository snapshotRepository;
    private final WorkloadProfileRepository profileRepository;

    // Gauge metrics that can be averaged directly
    private static final Set<String> GAUGE_METRICS = Set.of(
        "read_write_ratio",
        "cache_hit_ratio",
        "innodb_buffer_hit_ratio",
        "conn_active",
        "threads_running",
        "numbackends"
    );

    // Cumulative counters that need delta (rate) computation
    private static final Set<String> CUMULATIVE_COUNTERS = Set.of(
        "total_seq_scan",
        "total_idx_scan",
        "total_tup_ins",
        "total_tup_upd",
        "total_tup_del",
        "xact_commit",
        "xact_rollback",
        "slow_queries",
        "temp_files",
        "blks_read",
        "blks_hit",
        // MySQL equivalents (lowercase as stored)
        "com_select",
        "com_insert",
        "com_update",
        "com_delete",
        "innodb_rows_read",
        "innodb_rows_inserted",
        "innodb_rows_updated",
        "innodb_rows_deleted"
    );

    // All metrics used for workload classification
    private static final List<String> CLASSIFICATION_METRICS = List.of(
        "read_write_ratio",
        "cache_hit_ratio",
        "innodb_buffer_hit_ratio",
        "total_seq_scan",
        "total_idx_scan",
        "conn_active",
        "total_tup_ins",
        "total_tup_upd",
        "total_tup_del",
        "xact_commit",
        "xact_rollback",
        "slow_queries",
        "temp_files"
    );

    /**
     * Characterize workload for a connection based on recent metrics.
     */
    @Transactional
    public WorkloadProfile characterizeWorkload(String connectionId) {
        log.info("Characterizing workload for connection: {}", connectionId);

        // Get recent snapshots
        List<WorkloadMetricsSnapshot> snapshots = snapshotRepository
            .findByConnectionIdOrderByCollectedAtDesc(connectionId, PageRequest.of(0, 50));

        if (snapshots.isEmpty()) {
            log.warn("No metrics snapshots available for connection: {}", connectionId);
            return createDefaultProfile(connectionId);
        }

        // Extract and aggregate metrics
        Map<String, List<Double>> metricHistory = aggregateMetrics(snapshots);

        // Classify workload type
        WorkloadProfile.WorkloadType workloadType = classifyWorkloadType(metricHistory);
        double confidence = calculateClassificationConfidence(metricHistory, workloadType);

        // Generate fingerprint vector
        List<Double> fingerprintVector = generateFingerprintVector(metricHistory);

        // Identify selected metrics (simplified factor analysis)
        List<String> selectedMetrics = selectRepresentativeMetrics(metricHistory);

        // Extract key metric values for display
        Map<String, Double> keyMetricValues = extractKeyMetricValues(metricHistory);

        // Generate classification reasoning
        String reasoning = generateClassificationReasoning(metricHistory, workloadType, keyMetricValues);

        // Create or update profile
        WorkloadProfile profile = profileRepository.findByConnectionId(connectionId)
            .orElse(WorkloadProfile.builder()
                .connectionId(connectionId)
                .profiledAt(LocalDateTime.now())
                .build());

        profile.setWorkloadType(workloadType);
        profile.setClassificationConfidence(confidence);
        profile.setFingerprintVector(fingerprintVector);
        profile.setSelectedMetrics(selectedMetrics);
        profile.setKeyMetricValues(keyMetricValues);
        profile.setClassificationReasoning(reasoning);
        profile.setLastUpdatedAt(LocalDateTime.now());

        // Determine subtype
        profile.setWorkloadSubtype(determineSubtype(metricHistory, workloadType));

        profileRepository.save(profile);

        log.info("Workload characterized for {}: {} (confidence: {}%)",
            connectionId, workloadType, String.format("%.1f", confidence));

        return profile;
    }

    /**
     * Aggregate metrics from multiple snapshots.
     * CRITICAL: Compute rates (deltas per second) for cumulative counters,
     * not raw averages. This is based on OtterTune's approach.
     */
    private Map<String, List<Double>> aggregateMetrics(List<WorkloadMetricsSnapshot> snapshots) {
        Map<String, List<Double>> history = new HashMap<>();

        if (snapshots.isEmpty()) {
            return history;
        }

        // Sort snapshots by time (oldest first) for delta computation
        List<WorkloadMetricsSnapshot> sortedSnapshots = new ArrayList<>(snapshots);
        sortedSnapshots.sort(Comparator.comparing(WorkloadMetricsSnapshot::getCollectedAt));

        WorkloadMetricsSnapshot prevSnapshot = null;

        for (WorkloadMetricsSnapshot snapshot : sortedSnapshots) {
            Map<String, Object> metrics = snapshot.getRawMetrics();
            if (metrics == null) continue;

            // Calculate interval between snapshots
            long intervalSeconds = 1;
            if (prevSnapshot != null && prevSnapshot.getCollectedAt() != null && snapshot.getCollectedAt() != null) {
                intervalSeconds = Math.max(1,
                    Duration.between(prevSnapshot.getCollectedAt(), snapshot.getCollectedAt()).getSeconds());
            }

            // Process gauge metrics (can be collected directly)
            for (String key : GAUGE_METRICS) {
                Object value = metrics.get(key);
                if (value instanceof Number) {
                    history.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(((Number) value).doubleValue());
                }
            }

            // Process cumulative counters (compute rate = delta / interval)
            if (prevSnapshot != null && prevSnapshot.getRawMetrics() != null) {
                Map<String, Object> prevMetrics = prevSnapshot.getRawMetrics();

                for (String key : CUMULATIVE_COUNTERS) {
                    Object newVal = metrics.get(key);
                    Object oldVal = prevMetrics.get(key);

                    if (newVal instanceof Number && oldVal instanceof Number) {
                        double delta = ((Number) newVal).doubleValue() - ((Number) oldVal).doubleValue();
                        // Handle counter resets (negative delta) - skip this sample
                        if (delta >= 0) {
                            double rate = delta / intervalSeconds;
                            history.computeIfAbsent(key + "_rate", k -> new ArrayList<>()).add(rate);
                        }
                    }
                }
            }

            prevSnapshot = snapshot;
        }

        // Compute derived metrics from rates
        computeDerivedMetrics(history);

        return history;
    }

    /**
     * Compute derived metrics from raw rates.
     */
    private void computeDerivedMetrics(Map<String, List<Double>> history) {
        // Compute index usage ratio from scan rates
        List<Double> seqScanRates = history.getOrDefault("total_seq_scan_rate", List.of());
        List<Double> idxScanRates = history.getOrDefault("total_idx_scan_rate", List.of());

        if (!seqScanRates.isEmpty() && !idxScanRates.isEmpty()) {
            List<Double> indexRatios = new ArrayList<>();
            int minSize = Math.min(seqScanRates.size(), idxScanRates.size());
            for (int i = 0; i < minSize; i++) {
                double total = seqScanRates.get(i) + idxScanRates.get(i);
                if (total > 0) {
                    indexRatios.add(idxScanRates.get(i) / total);
                }
            }
            if (!indexRatios.isEmpty()) {
                history.put("index_usage_ratio", indexRatios);
            }
        }

        // Compute write composition ratio (insert vs update)
        List<Double> insertRates = history.getOrDefault("total_tup_ins_rate", List.of());
        List<Double> updateRates = history.getOrDefault("total_tup_upd_rate", List.of());

        if (!insertRates.isEmpty() && !updateRates.isEmpty()) {
            List<Double> insertRatios = new ArrayList<>();
            int minSize = Math.min(insertRates.size(), updateRates.size());
            for (int i = 0; i < minSize; i++) {
                double total = insertRates.get(i) + updateRates.get(i);
                if (total > 0) {
                    insertRatios.add(insertRates.get(i) / total);
                }
            }
            if (!insertRatios.isEmpty()) {
                history.put("insert_vs_update_ratio", insertRatios);
            }
        }

        // Compute throughput (transactions per second)
        List<Double> commitRates = history.getOrDefault("xact_commit_rate", List.of());
        if (!commitRates.isEmpty()) {
            history.put("throughput_tps", new ArrayList<>(commitRates));
        }
    }

    /**
     * Classify the workload type based on metric patterns.
     * Uses rate-based metrics (operations/second) for cumulative counters.
     */
    private WorkloadProfile.WorkloadType classifyWorkloadType(Map<String, List<Double>> metricHistory) {
        // Get average read/write ratio (gauge metric)
        double avgReadWriteRatio = getAverageMetric(metricHistory, "read_write_ratio", 0.5);

        // Get index usage ratio (computed from scan rates)
        double indexUsageRatio = getAverageMetric(metricHistory, "index_usage_ratio", -1);
        if (indexUsageRatio < 0) {
            // Fallback to computing from rates if derived metric not available
            double seqScanRate = getAverageMetric(metricHistory, "total_seq_scan_rate", 0);
            double idxScanRate = getAverageMetric(metricHistory, "total_idx_scan_rate", 0);
            double totalScanRate = seqScanRate + idxScanRate;
            indexUsageRatio = totalScanRate > 0 ? idxScanRate / totalScanRate : 0.5;
        }

        // Get write patterns from rates (operations per second)
        double insertRate = getAverageMetric(metricHistory, "total_tup_ins_rate", 0);
        double updateRate = getAverageMetric(metricHistory, "total_tup_upd_rate", 0);
        double deleteRate = getAverageMetric(metricHistory, "total_tup_del_rate", 0);
        double totalWriteRate = insertRate + updateRate + deleteRate;

        // Get throughput for additional context
        double throughputTps = getAverageMetric(metricHistory, "throughput_tps", 0);

        log.debug("Classification metrics: readWriteRatio={}, indexUsage={}, " +
                  "insertRate={}/s, updateRate={}/s, throughput={} tps",
            String.format("%.2f", avgReadWriteRatio),
            String.format("%.2f", indexUsageRatio),
            String.format("%.1f", insertRate),
            String.format("%.1f", updateRate),
            String.format("%.1f", throughputTps));

        // Determine workload type based on patterns
        if (avgReadWriteRatio > 0.9) {
            // 90%+ reads
            if (indexUsageRatio < 0.3) {
                return WorkloadProfile.WorkloadType.OLAP; // Full scans = analytical
            } else {
                return WorkloadProfile.WorkloadType.READ_HEAVY;
            }
        } else if (avgReadWriteRatio < 0.3) {
            // 70%+ writes
            if (updateRate > insertRate * 2) {
                return WorkloadProfile.WorkloadType.WRITE_HEAVY; // Update heavy
            } else if (insertRate > updateRate + deleteRate) {
                return WorkloadProfile.WorkloadType.BATCH; // Insert heavy = batch
            } else {
                return WorkloadProfile.WorkloadType.WRITE_HEAVY;
            }
        } else if (indexUsageRatio > 0.7 && avgReadWriteRatio > 0.5) {
            // High index usage + mixed reads = OLTP
            return WorkloadProfile.WorkloadType.OLTP;
        } else {
            return WorkloadProfile.WorkloadType.MIXED;
        }
    }

    /**
     * Calculate confidence in the classification.
     */
    private double calculateClassificationConfidence(
            Map<String, List<Double>> metricHistory,
            WorkloadProfile.WorkloadType workloadType) {

        // Base confidence on data availability
        int metricCount = metricHistory.size();
        int sampleCount = metricHistory.values().stream()
            .mapToInt(List::size).max().orElse(0);

        double dataConfidence = Math.min(1.0, metricCount / 8.0) *
                               Math.min(1.0, sampleCount / 20.0);

        // Adjust based on how clearly the workload matches patterns
        double readWriteRatio = getAverageMetric(metricHistory, "read_write_ratio", 0.5);

        double patternConfidence;
        if (readWriteRatio > 0.85 || readWriteRatio < 0.15) {
            patternConfidence = 0.9; // Clear pattern
        } else if (readWriteRatio > 0.7 || readWriteRatio < 0.3) {
            patternConfidence = 0.7; // Moderate pattern
        } else {
            patternConfidence = 0.5; // Mixed/unclear
        }

        return (dataConfidence * 0.4 + patternConfidence * 0.6) * 100;
    }

    /**
     * Generate fingerprint vector from metrics.
     * Uses rate-based metrics for cumulative counters to ensure consistent fingerprints.
     */
    private List<Double> generateFingerprintVector(Map<String, List<Double>> metricHistory) {
        List<Double> vector = new ArrayList<>();

        // 1. Read/Write ratio (gauge metric, use directly)
        vector.add(normalizeMetric(getAverageMetric(metricHistory, "read_write_ratio", 0.5), 0, 1));

        // 2. Cache hit ratio (gauge metric)
        vector.add(normalizeMetric(getAverageMetric(metricHistory, "cache_hit_ratio", 0.9), 0, 1));

        // 3. Index usage ratio from rate-based metrics
        double indexUsageRatio = getAverageMetric(metricHistory, "index_usage_ratio", -1);
        if (indexUsageRatio < 0) {
            double seqScanRate = getAverageMetric(metricHistory, "total_seq_scan_rate", 0);
            double idxScanRate = getAverageMetric(metricHistory, "total_idx_scan_rate", 0);
            double totalScanRate = seqScanRate + idxScanRate;
            indexUsageRatio = totalScanRate > 0 ? idxScanRate / totalScanRate : 0.5;
        }
        vector.add(indexUsageRatio);

        // 4. Transaction success ratio from rates
        double commitRate = getAverageMetric(metricHistory, "xact_commit_rate", 1);
        double rollbackRate = getAverageMetric(metricHistory, "xact_rollback_rate", 0);
        double totalTxRate = commitRate + rollbackRate;
        vector.add(totalTxRate > 0 ? commitRate / totalTxRate : 1.0);

        // 5. Active connections (gauge metric, normalized)
        vector.add(normalizeMetric(getAverageMetric(metricHistory, "conn_active", 1), 0, 100));

        // 6. Temp file usage indicator (rate-based if available)
        double tempFileRate = getAverageMetric(metricHistory, "temp_files_rate", -1);
        if (tempFileRate >= 0) {
            vector.add(tempFileRate > 0.1 ? 1.0 : 0.0); // >0.1 temp files/sec indicates complex queries
        } else {
            vector.add(0.0);
        }

        // 7. Slow query ratio (rate of slow queries vs total throughput)
        double slowQueryRate = getAverageMetric(metricHistory, "slow_queries_rate", 0);
        double throughputTps = getAverageMetric(metricHistory, "throughput_tps", 1);
        vector.add(throughputTps > 0 ? Math.min(1.0, slowQueryRate / throughputTps * 10) : 0.0);

        // 8. Write composition ratio from rate-based metrics
        double insertVsUpdateRatio = getAverageMetric(metricHistory, "insert_vs_update_ratio", -1);
        if (insertVsUpdateRatio < 0) {
            double insertRate = getAverageMetric(metricHistory, "total_tup_ins_rate", 0);
            double updateRate = getAverageMetric(metricHistory, "total_tup_upd_rate", 0);
            double totalWriteRate = insertRate + updateRate;
            insertVsUpdateRatio = totalWriteRate > 0 ? insertRate / totalWriteRate : 0.5;
        }
        vector.add(insertVsUpdateRatio);

        return vector;
    }

    /**
     * Select representative metrics (simplified factor analysis).
     */
    private List<String> selectRepresentativeMetrics(Map<String, List<Double>> metricHistory) {
        // Calculate variance for each metric
        Map<String, Double> variances = new HashMap<>();

        for (Map.Entry<String, List<Double>> entry : metricHistory.entrySet()) {
            List<Double> values = entry.getValue();
            if (values.size() > 1) {
                double variance = calculateVariance(values);
                variances.put(entry.getKey(), variance);
            }
        }

        // Select metrics with highest variance (most informative)
        return variances.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(8)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Determine workload subtype for more specific classification.
     * Uses rate-based metrics (operations/second) for accurate comparison.
     */
    private String determineSubtype(
            Map<String, List<Double>> metricHistory,
            WorkloadProfile.WorkloadType workloadType) {

        return switch (workloadType) {
            case OLTP -> {
                double cacheHit = getAverageMetric(metricHistory, "cache_hit_ratio", 0.9);
                if (cacheHit > 0.99) yield "High-Cache OLTP";
                else if (cacheHit > 0.95) yield "Standard OLTP";
                else yield "Cache-Pressured OLTP";
            }
            case OLAP -> {
                // Use temp_files_rate if available, else fall back to raw temp_files for gauge
                double tempFileRate = getAverageMetric(metricHistory, "temp_files_rate", -1);
                if (tempFileRate < 0) {
                    tempFileRate = getAverageMetric(metricHistory, "temp_files", 0);
                }
                if (tempFileRate > 1) yield "Heavy Analytical"; // >1 temp file/sec
                else yield "Standard Analytical";
            }
            case WRITE_HEAVY -> {
                // Use rate-based metrics (operations/second) for accurate comparison
                double insertRate = getAverageMetric(metricHistory, "total_tup_ins_rate", 0);
                double updateRate = getAverageMetric(metricHistory, "total_tup_upd_rate", 0);
                if (insertRate > updateRate * 2) yield "Insert-Heavy";
                else if (updateRate > insertRate * 2) yield "Update-Heavy";
                else yield "Mixed Writes";
            }
            case READ_HEAVY -> "Read-Dominant";
            case BATCH -> "Batch Processing";
            case MIXED -> "Mixed Workload";
            default -> "Unknown";
        };
    }

    /**
     * Extract key metric values for display.
     * Uses rate-based metrics where appropriate (operations/second for cumulative counters).
     */
    private Map<String, Double> extractKeyMetricValues(Map<String, List<Double>> metricHistory) {
        Map<String, Double> values = new LinkedHashMap<>();

        // Core classification metrics (gauge metrics - can use directly)
        values.put("read_write_ratio", getAverageMetric(metricHistory, "read_write_ratio", -1));
        values.put("cache_hit_ratio", getAverageMetric(metricHistory, "cache_hit_ratio", -1));
        if (values.get("cache_hit_ratio") < 0) {
            values.put("cache_hit_ratio", getAverageMetric(metricHistory, "innodb_buffer_hit_ratio", -1));
        }

        // Index usage - prefer pre-computed derived metric, fallback to rate-based calculation
        double indexUsageRatio = getAverageMetric(metricHistory, "index_usage_ratio", -1);
        if (indexUsageRatio < 0) {
            double seqScanRate = getAverageMetric(metricHistory, "total_seq_scan_rate", 0);
            double idxScanRate = getAverageMetric(metricHistory, "total_idx_scan_rate", 0);
            double totalScanRate = seqScanRate + idxScanRate;
            if (totalScanRate > 0) {
                indexUsageRatio = idxScanRate / totalScanRate;
            }
        }
        if (indexUsageRatio >= 0) {
            values.put("index_usage_ratio", indexUsageRatio);
        }

        // Activity metrics (gauge)
        values.put("active_connections", getAverageMetric(metricHistory, "conn_active", -1));

        // Throughput metrics (rate-based, operations/second)
        double throughputTps = getAverageMetric(metricHistory, "throughput_tps", -1);
        if (throughputTps >= 0) {
            values.put("throughput_tps", throughputTps);
        }

        // Transaction success ratio from rates
        double commitRate = getAverageMetric(metricHistory, "xact_commit_rate", 0);
        double rollbackRate = getAverageMetric(metricHistory, "xact_rollback_rate", 0);
        double totalTxRate = commitRate + rollbackRate;
        if (totalTxRate > 0) {
            values.put("commit_ratio", commitRate / totalTxRate);
        }

        // Slow query rate (if available)
        double slowQueryRate = getAverageMetric(metricHistory, "slow_queries_rate", -1);
        if (slowQueryRate >= 0) {
            values.put("slow_queries_per_sec", slowQueryRate);
        }

        // Remove entries with no data (-1)
        values.entrySet().removeIf(e -> e.getValue() < 0);

        return values;
    }

    /**
     * Generate human-readable classification reasoning.
     */
    private String generateClassificationReasoning(
            Map<String, List<Double>> metricHistory,
            WorkloadProfile.WorkloadType workloadType,
            Map<String, Double> keyMetrics) {

        StringBuilder reasoning = new StringBuilder();

        double readWriteRatio = keyMetrics.getOrDefault("read_write_ratio", 0.5);
        double cacheHitRatio = keyMetrics.getOrDefault("cache_hit_ratio", 0.0);
        double indexUsageRatio = keyMetrics.getOrDefault("index_usage_ratio", 0.5);

        reasoning.append("Classification: ").append(workloadType).append("\n\n");

        // Read/Write analysis
        if (readWriteRatio > 0.9) {
            reasoning.append("• Read-dominant: ").append(String.format("%.0f%%", readWriteRatio * 100))
                .append(" of operations are reads\n");
        } else if (readWriteRatio < 0.3) {
            reasoning.append("• Write-dominant: ").append(String.format("%.0f%%", (1 - readWriteRatio) * 100))
                .append(" of operations are writes\n");
        } else {
            reasoning.append("• Balanced read/write: ").append(String.format("%.0f%%", readWriteRatio * 100))
                .append(" reads, ").append(String.format("%.0f%%", (1 - readWriteRatio) * 100)).append(" writes\n");
        }

        // Index usage analysis
        if (indexUsageRatio > 0.7) {
            reasoning.append("• High index usage (").append(String.format("%.0f%%", indexUsageRatio * 100))
                .append(") suggests point queries typical of OLTP\n");
        } else if (indexUsageRatio < 0.3) {
            reasoning.append("• Low index usage (").append(String.format("%.0f%%", indexUsageRatio * 100))
                .append(") suggests full scans typical of analytical workloads\n");
        }

        // Cache analysis
        if (cacheHitRatio > 0.95) {
            reasoning.append("• Excellent cache hit ratio (").append(String.format("%.1f%%", cacheHitRatio * 100))
                .append(") - working set fits in memory\n");
        } else if (cacheHitRatio > 0 && cacheHitRatio < 0.85) {
            reasoning.append("• Low cache hit ratio (").append(String.format("%.1f%%", cacheHitRatio * 100))
                .append(") - consider increasing buffer pool\n");
        }

        // Workload-specific recommendations
        reasoning.append("\nRecommendation: ");
        switch (workloadType) {
            case OLTP -> reasoning.append("Optimize for low latency with connection pooling and index tuning.");
            case OLAP -> reasoning.append("Consider columnar storage, materialized views, and query parallelism.");
            case READ_HEAVY -> reasoning.append("Add read replicas and implement caching strategies.");
            case WRITE_HEAVY -> reasoning.append("Tune checkpoint frequency and consider batch commits.");
            case BATCH -> reasoning.append("Schedule during off-peak hours and use bulk insert operations.");
            case MIXED -> reasoning.append("Balance configuration for both read and write performance.");
            default -> reasoning.append("Monitor workload patterns to refine classification.");
        }

        return reasoning.toString();
    }

    /**
     * Create a default profile when no data is available.
     */
    private WorkloadProfile createDefaultProfile(String connectionId) {
        return WorkloadProfile.builder()
            .connectionId(connectionId)
            .workloadType(WorkloadProfile.WorkloadType.UNKNOWN)
            .classificationConfidence(0.0)
            .fingerprintVector(List.of(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5))
            .selectedMetrics(List.of())
            .keyMetricValues(Map.of())
            .classificationReasoning("Insufficient data for classification. Collect more metrics snapshots.")
            .profiledAt(LocalDateTime.now())
            .build();
    }

    private double getAverageMetric(Map<String, List<Double>> history, String key, double defaultValue) {
        List<Double> values = history.get(key);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(defaultValue);
    }

    private double calculateVariance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average()
            .orElse(0);
    }

    private double normalizeMetric(double value, double min, double max) {
        if (max <= min) return 0.5;
        return Math.max(0, Math.min(1, (value - min) / (max - min)));
    }

    /**
     * Get profile for a connection.
     */
    public Optional<WorkloadProfile> getProfile(String connectionId) {
        return profileRepository.findByConnectionId(connectionId);
    }

    /**
     * Find similar workload profiles.
     */
    public List<WorkloadProfile> findSimilarProfiles(String connectionId, int limit) {
        Optional<WorkloadProfile> profileOpt = profileRepository.findByConnectionId(connectionId);
        if (profileOpt.isEmpty()) {
            return List.of();
        }

        WorkloadProfile profile = profileOpt.get();
        List<WorkloadProfile> sameType = profileRepository.findSimilarProfiles(
            profile.getWorkloadType(), connectionId);

        // Sort by fingerprint similarity
        List<Double> targetVector = profile.getFingerprintVector();
        if (targetVector == null || targetVector.isEmpty()) {
            return sameType.stream().limit(limit).collect(Collectors.toList());
        }

        return sameType.stream()
            .filter(p -> p.getFingerprintVector() != null && !p.getFingerprintVector().isEmpty())
            .sorted((a, b) -> {
                double distA = euclideanDistance(targetVector, a.getFingerprintVector());
                double distB = euclideanDistance(targetVector, b.getFingerprintVector());
                return Double.compare(distA, distB);
            })
            .limit(limit)
            .collect(Collectors.toList());
    }

    private double euclideanDistance(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) {
            return Double.MAX_VALUE;
        }
        double sum = 0;
        for (int i = 0; i < a.size(); i++) {
            sum += Math.pow(a.get(i) - b.get(i), 2);
        }
        return Math.sqrt(sum);
    }
}
