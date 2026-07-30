package com.dbaagent.service;

import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.repository.QueryFingerprintRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for tracking query fingerprints (normalized query patterns) over time.
 * Enables trend analysis and performance comparison.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryFingerprintService {

    private final QueryFingerprintRepository fingerprintRepository;
    private static final int MAX_HISTORY_POINTS = 50;  // Keep last 50 observations

    @Data
    @Builder
    public static class FingerprintSummary {
        private int totalFingerprints;
        private int regressingCount;
        private int improvingCount;
        private int stableCount;
        private int criticalCount;
        private List<QueryFingerprint> topRegressing;
        private List<QueryFingerprint> topSlow;
        private Map<String, Integer> queryTypeDistribution;
    }

    @Data
    @Builder
    public static class FingerprintTrend {
        private String fingerprint;
        private String normalizedQuery;
        private List<TrendDataPoint> dataPoints;
        private Double baselineAvgMs;
        private Double currentAvgMs;
        private Double trendPercentage;
        private QueryFingerprint.TrendDirection direction;
    }

    @Data
    @Builder
    public static class TrendDataPoint {
        private LocalDateTime timestamp;
        private Double avgTimeMs;
        private Long callCount;
        private Long rowsExamined;
    }

    /**
     * Process slow query analysis and update fingerprints
     */
    @Transactional
    public List<QueryFingerprint> processAnalysis(String connectionId, SlowQueryAnalysis analysis) {
        if (analysis == null || analysis.getTopSlowQueries() == null) {
            return Collections.emptyList();
        }

        List<QueryFingerprint> updated = new ArrayList<>();

        for (SlowQuery query : analysis.getTopSlowQueries()) {
            if (query.getQueryText() == null || query.getQueryText().isBlank()) {
                continue;
            }

            try {
                QueryFingerprint fingerprint = updateOrCreateFingerprint(connectionId, query);
                updated.add(fingerprint);
            } catch (Exception e) {
                log.warn("Failed to process fingerprint for query: {}", e.getMessage());
            }
        }

        log.info("Processed {} query fingerprints for connection: {}", updated.size(), connectionId);
        return updated;
    }

    /**
     * Update existing fingerprint or create new one
     */
    @Transactional
    public QueryFingerprint updateOrCreateFingerprint(String connectionId, SlowQuery query) {
        String normalized = normalizeQuery(query.getQueryText());
        String hash = generateFingerprint(normalized);

        Optional<QueryFingerprint> existing = fingerprintRepository
            .findByConnectionIdAndFingerprint(connectionId, hash);

        QueryFingerprint fingerprint;
        if (existing.isPresent()) {
            fingerprint = existing.get();
            addHistoryPoint(fingerprint, query);
            fingerprint.updateFromSlowQuery(query);
        } else {
            String sampleQuery = query.getSampleQuery() != null ? query.getSampleQuery() : query.getQueryText();
            fingerprint = QueryFingerprint.builder()
                .connectionId(connectionId)
                .fingerprint(hash)
                .normalizedQuery(truncate(normalized, 2000))
                .sampleQuery(truncate(sampleQuery, 10000))
                .affectedTables(query.getAffectedTables())
                .queryType(extractQueryType(query.getQueryText()))
                .build();
            fingerprint.updateFromSlowQuery(query);
        }

        return fingerprintRepository.save(fingerprint);
    }

    /**
     * Get fingerprint summary for a connection
     */
    public FingerprintSummary getSummary(String connectionId) {
        List<QueryFingerprint> all = fingerprintRepository
            .findByConnectionIdOrderByLastSeenAtDesc(connectionId);

        int regressing = 0, improving = 0, stable = 0, critical = 0;
        Map<String, Integer> typeDistribution = new HashMap<>();

        for (QueryFingerprint fp : all) {
            if (fp.getTrendDirection() != null) {
                switch (fp.getTrendDirection()) {
                    case IMPROVING -> improving++;
                    case DEGRADING -> regressing++;
                    case CRITICAL -> { critical++; regressing++; }
                    case STABLE -> stable++;
                }
            } else {
                stable++;
            }

            if (fp.getQueryType() != null) {
                typeDistribution.merge(fp.getQueryType(), 1, Integer::sum);
            }
        }

        List<QueryFingerprint> topRegressing = fingerprintRepository
            .findByConnectionIdAndIsRegressingTrueOrderByTrendPercentageDesc(connectionId)
            .stream().limit(5).toList();

        List<QueryFingerprint> topSlow = fingerprintRepository
            .findTopSlowQueries(connectionId, PageRequest.of(0, 5));

        return FingerprintSummary.builder()
            .totalFingerprints(all.size())
            .regressingCount(regressing)
            .improvingCount(improving)
            .stableCount(stable)
            .criticalCount(critical)
            .topRegressing(topRegressing)
            .topSlow(topSlow)
            .queryTypeDistribution(typeDistribution)
            .build();
    }

    /**
     * Get trend data for a specific fingerprint
     */
    public FingerprintTrend getTrend(String fingerprintId) {
        QueryFingerprint fp = fingerprintRepository.findById(fingerprintId)
            .orElseThrow(() -> new IllegalArgumentException("Fingerprint not found: " + fingerprintId));

        List<TrendDataPoint> dataPoints = new ArrayList<>();
        if (fp.getPerformanceHistory() != null) {
            for (Map<String, Object> point : fp.getPerformanceHistory()) {
                dataPoints.add(TrendDataPoint.builder()
                    .timestamp(LocalDateTime.parse((String) point.get("timestamp")))
                    .avgTimeMs(((Number) point.get("avgTimeMs")).doubleValue())
                    .callCount(point.get("callCount") != null ?
                        ((Number) point.get("callCount")).longValue() : null)
                    .rowsExamined(point.get("rowsExamined") != null ?
                        ((Number) point.get("rowsExamined")).longValue() : null)
                    .build());
            }
        }

        return FingerprintTrend.builder()
            .fingerprint(fp.getFingerprint())
            .normalizedQuery(fp.getNormalizedQuery())
            .dataPoints(dataPoints)
            .baselineAvgMs(fp.getBaselineAvgTimeMs())
            .currentAvgMs(fp.getCurrentAvgTimeMs())
            .trendPercentage(fp.getTrendPercentage())
            .direction(fp.getTrendDirection())
            .build();
    }

    /**
     * Get all fingerprints for a connection with optional filtering
     */
    public List<QueryFingerprint> getFingerprints(
        String connectionId,
        String queryType,
        QueryFingerprint.TrendDirection trendDirection,
        Boolean regressingOnly,
        int limit
    ) {
        List<QueryFingerprint> results;

        if (regressingOnly != null && regressingOnly) {
            results = fingerprintRepository
                .findByConnectionIdAndIsRegressingTrueOrderByTrendPercentageDesc(connectionId);
        } else if (trendDirection != null) {
            results = fingerprintRepository
                .findByConnectionIdAndTrendDirectionOrderByTrendPercentageDesc(connectionId, trendDirection);
        } else if (queryType != null && !queryType.isBlank()) {
            results = fingerprintRepository
                .findByConnectionIdAndQueryTypeOrderByCurrentAvgTimeMsDesc(connectionId, queryType);
        } else {
            results = fingerprintRepository
                .findByConnectionIdOrderByLastSeenAtDesc(connectionId);
        }

        return results.stream().limit(limit).toList();
    }

    /**
     * Compare two fingerprints
     */
    public Map<String, Object> compareFingerprints(String fingerprintId1, String fingerprintId2) {
        QueryFingerprint fp1 = fingerprintRepository.findById(fingerprintId1)
            .orElseThrow(() -> new IllegalArgumentException("Fingerprint not found: " + fingerprintId1));
        QueryFingerprint fp2 = fingerprintRepository.findById(fingerprintId2)
            .orElseThrow(() -> new IllegalArgumentException("Fingerprint not found: " + fingerprintId2));

        Map<String, Object> comparison = new HashMap<>();
        comparison.put("fingerprint1", fp1);
        comparison.put("fingerprint2", fp2);

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("avgTimeDiff", (fp1.getCurrentAvgTimeMs() != null ? fp1.getCurrentAvgTimeMs() : 0) -
                                   (fp2.getCurrentAvgTimeMs() != null ? fp2.getCurrentAvgTimeMs() : 0));
        metrics.put("callCountDiff", (fp1.getCurrentCallCount() != null ? fp1.getCurrentCallCount() : 0) -
                                     (fp2.getCurrentCallCount() != null ? fp2.getCurrentCallCount() : 0));

        comparison.put("metrics", metrics);
        return comparison;
    }

    /**
     * Reset baseline for a fingerprint
     */
    @Transactional
    public QueryFingerprint resetBaseline(String fingerprintId) {
        QueryFingerprint fp = fingerprintRepository.findById(fingerprintId)
            .orElseThrow(() -> new IllegalArgumentException("Fingerprint not found: " + fingerprintId));

        fp.setBaselineAvgTimeMs(fp.getCurrentAvgTimeMs());
        fp.setBaselineMaxTimeMs(fp.getCurrentMaxTimeMs());
        fp.setBaselineCallCount(fp.getCurrentCallCount());
        fp.setBaselineDate(LocalDateTime.now());
        fp.setIsRegressing(false);
        fp.setRegressionDetectedAt(null);
        fp.calculateTrend();

        return fingerprintRepository.save(fp);
    }

    /**
     * Cleanup old fingerprints not seen in the specified days
     */
    @Transactional
    public int cleanupOldFingerprints(String connectionId, int retentionDays) {
        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
        List<QueryFingerprint> toDelete = fingerprintRepository
            .findByConnectionIdOrderByLastSeenAtDesc(connectionId).stream()
            .filter(fp -> fp.getLastSeenAt() != null && fp.getLastSeenAt().isBefore(before))
            .toList();

        fingerprintRepository.deleteAll(toDelete);
        log.info("Cleaned up {} old fingerprints for connection: {}", toDelete.size(), connectionId);
        return toDelete.size();
    }

    private void addHistoryPoint(QueryFingerprint fingerprint, SlowQuery query) {
        List<Map<String, Object>> history = fingerprint.getPerformanceHistory();
        if (history == null) {
            history = new ArrayList<>();
        }

        Map<String, Object> point = new HashMap<>();
        point.put("timestamp", LocalDateTime.now().toString());
        point.put("avgTimeMs", query.getAvgExecutionTimeMs());
        point.put("callCount", query.getCallCount());
        point.put("rowsExamined", query.getRowsExamined());

        history.add(point);

        // Keep only last N points
        if (history.size() > MAX_HISTORY_POINTS) {
            history = history.subList(history.size() - MAX_HISTORY_POINTS, history.size());
        }

        fingerprint.setPerformanceHistory(history);
    }

    /**
     * Normalize a query by replacing literals with placeholders
     */
    private String normalizeQuery(String query) {
        if (query == null) return "";

        // Use centralized QueryNormalizer which handles sanitization and normalization
        return com.dbaagent.util.QueryNormalizer.normalize(query);
    }

    /**
     * Compute the canonical 16-char fingerprint for a normalized query.
     * This is the authoritative fingerprint format used in the query_fingerprints table.
     * All subsystems (optimization cache, candidates, benchmarks) MUST use this format
     * to ensure consistent cross-table lookups.
     *
     * @param normalizedQuery the query after {@link com.dbaagent.util.QueryNormalizer#normalize}
     * @return 16-char hex SHA-256 prefix
     */
    public static String computeCanonicalFingerprint(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalizedQuery.toLowerCase().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(normalizedQuery.hashCode());
        }
    }

    private String generateFingerprint(String normalizedQuery) {
        return computeCanonicalFingerprint(normalizedQuery);
    }

    private String extractQueryType(String query) {
        if (query == null) return "UNKNOWN";
        String upper = query.trim().toUpperCase();
        if (upper.startsWith("SELECT")) return "SELECT";
        if (upper.startsWith("INSERT")) return "INSERT";
        if (upper.startsWith("UPDATE")) return "UPDATE";
        if (upper.startsWith("DELETE")) return "DELETE";
        if (upper.startsWith("CREATE")) return "DDL";
        if (upper.startsWith("ALTER")) return "DDL";
        if (upper.startsWith("DROP")) return "DDL";
        return "OTHER";
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength);
    }
}
