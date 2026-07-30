package com.dbaagent.service.brain.classification;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.TableStatsHistoryRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for predicting table growth trends.
 *
 * Analyzes:
 * - Historical growth patterns from TableStatsHistory
 * - Current table sizes
 * - Growth rate (linear, exponential, polynomial)
 *
 * Predicts:
 * - Size in 30/90/365 days
 * - Days until reaching size thresholds
 * - Storage capacity planning recommendations
 *
 * Growth Categories:
 * - EXPLOSIVE: >50% monthly growth
 * - RAPID: 20-50% monthly growth
 * - STEADY: 5-20% monthly growth
 * - SLOW: 1-5% monthly growth
 * - STABLE: <1% monthly growth
 * - SHRINKING: Negative growth
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GrowthPredictionService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final TableStatsHistoryRepository tableStatsHistoryRepository;

    // Thresholds
    private static final long WARNING_SIZE_BYTES = 10L * 1024 * 1024 * 1024;  // 10 GB
    private static final long CRITICAL_SIZE_BYTES = 100L * 1024 * 1024 * 1024; // 100 GB
    private static final long ROW_WARNING_THRESHOLD = 50_000_000;   // 50M rows
    private static final long ROW_CRITICAL_THRESHOLD = 500_000_000; // 500M rows

    /**
     * Generate growth predictions for all tables in a connection.
     */
    public Map<String, GrowthPrediction> predictGrowth(String connectionId) {
        Map<String, GrowthPrediction> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            String dbType = connRequest.getDbType();

            // Get current table sizes
            Map<String, CurrentTableStats> currentStats = getCurrentStats(conn, dbType);

            // Get historical growth data
            Map<String, List<TableStatsHistory>> historyMap = getGrowthHistory(connectionId);

            // Calculate predictions for each table
            for (Map.Entry<String, CurrentTableStats> entry : currentStats.entrySet()) {
                String tableName = entry.getKey();
                CurrentTableStats stats = entry.getValue();
                List<TableStatsHistory> history = historyMap.getOrDefault(tableName, List.of());

                GrowthPrediction prediction = calculatePrediction(tableName, stats, history);
                results.put(tableName.toLowerCase(), prediction);
            }
        } catch (Exception e) {
            log.error("Error predicting growth for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    private Map<String, CurrentTableStats> getCurrentStats(Connection conn, String dbType) {
        Map<String, CurrentTableStats> results = new HashMap<>();
        String query;

        if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
            query = """
                SELECT
                    relname as table_name,
                    COALESCE(n_live_tup, 0) as row_count,
                    pg_total_relation_size(schemaname || '.' || relname) as size_bytes
                FROM pg_stat_user_tables
                WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                """;
        } else {
            query = """
                SELECT
                    TABLE_NAME as table_name,
                    TABLE_ROWS as row_count,
                    DATA_LENGTH + INDEX_LENGTH as size_bytes
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                AND TABLE_TYPE = 'BASE TABLE'
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                results.put(tableName, CurrentTableStats.builder()
                    .rowCount(rs.getLong("row_count"))
                    .sizeBytes(rs.getLong("size_bytes"))
                    .build());
            }
        } catch (Exception e) {
            log.debug("Error getting current stats: {}", e.getMessage());
        }

        return results;
    }

    private Map<String, List<TableStatsHistory>> getGrowthHistory(String connectionId) {
        // Get last 90 days of history
        LocalDateTime since = LocalDateTime.now().minusDays(90);
        LocalDateTime now = LocalDateTime.now();
        List<TableStatsHistory> allHistory = tableStatsHistoryRepository
            .findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(connectionId, since, now);

        return allHistory.stream()
            .collect(Collectors.groupingBy(h -> h.getTableName().toLowerCase()));
    }

    private GrowthPrediction calculatePrediction(String tableName, CurrentTableStats current,
                                                   List<TableStatsHistory> history) {
        List<String> alerts = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // Make a mutable copy and sort history by date
        List<TableStatsHistory> sortedHistory = new ArrayList<>(history);
        sortedHistory.sort(Comparator.comparing(TableStatsHistory::getSnapshotTimestamp));

        // Calculate growth metrics
        GrowthMetrics metrics = calculateGrowthMetrics(current, sortedHistory);

        // Determine growth category
        String growthCategory = categorizeGrowth(metrics.getMonthlyGrowthRate());

        // Calculate predictions
        long size30Days = predictSize(current.getSizeBytes(), metrics.getDailyGrowthRate(), 30);
        long size90Days = predictSize(current.getSizeBytes(), metrics.getDailyGrowthRate(), 90);
        long size365Days = predictSize(current.getSizeBytes(), metrics.getDailyGrowthRate(), 365);

        long rows30Days = predictRows(current.getRowCount(), metrics.getDailyRowGrowthRate(), 30);
        long rows90Days = predictRows(current.getRowCount(), metrics.getDailyRowGrowthRate(), 90);
        long rows365Days = predictRows(current.getRowCount(), metrics.getDailyRowGrowthRate(), 365);

        // Calculate days until thresholds
        Integer daysToWarning = null;
        Integer daysToCritical = null;
        Integer daysToRowWarning = null;
        Integer daysToRowCritical = null;

        if (metrics.getDailyGrowthRate() > 0) {
            if (current.getSizeBytes() < WARNING_SIZE_BYTES) {
                daysToWarning = calculateDaysToThreshold(current.getSizeBytes(), WARNING_SIZE_BYTES, metrics.getDailyGrowthRate());
            }
            if (current.getSizeBytes() < CRITICAL_SIZE_BYTES) {
                daysToCritical = calculateDaysToThreshold(current.getSizeBytes(), CRITICAL_SIZE_BYTES, metrics.getDailyGrowthRate());
            }
        }

        if (metrics.getDailyRowGrowthRate() > 0) {
            if (current.getRowCount() < ROW_WARNING_THRESHOLD) {
                daysToRowWarning = calculateDaysToRowThreshold(current.getRowCount(), ROW_WARNING_THRESHOLD, metrics.getDailyRowGrowthRate());
            }
            if (current.getRowCount() < ROW_CRITICAL_THRESHOLD) {
                daysToRowCritical = calculateDaysToRowThreshold(current.getRowCount(), ROW_CRITICAL_THRESHOLD, metrics.getDailyRowGrowthRate());
            }
        }

        // Generate alerts and recommendations
        if ("EXPLOSIVE".equals(growthCategory)) {
            alerts.add("Table growing at explosive rate (>50% monthly)");
            recommendations.add("Urgent: Implement data retention policy");
            recommendations.add("Consider partitioning immediately");
            recommendations.add("Review application logic for excessive writes");
        } else if ("RAPID".equals(growthCategory)) {
            alerts.add("Rapid growth detected (20-50% monthly)");
            recommendations.add("Plan for storage expansion within 3 months");
            recommendations.add("Evaluate partitioning strategy");
        } else if ("STEADY".equals(growthCategory)) {
            recommendations.add("Monitor storage capacity quarterly");
            recommendations.add("Consider archival policy for data older than 1 year");
        }

        if (daysToWarning != null && daysToWarning < 90) {
            alerts.add(String.format("Will reach 10GB warning threshold in %d days", daysToWarning));
        }
        if (daysToCritical != null && daysToCritical < 180) {
            alerts.add(String.format("Will reach 100GB critical threshold in %d days", daysToCritical));
        }

        if (daysToRowWarning != null && daysToRowWarning < 90) {
            alerts.add(String.format("Will reach 50M rows in %d days - consider partitioning", daysToRowWarning));
        }

        // Confidence based on history availability
        double confidence = calculateConfidence(sortedHistory.size());

        String reasoning = String.format(
            "Based on %d historical data points over %d days. Current: %,d rows, %.2f GB",
            sortedHistory.size(),
            sortedHistory.isEmpty() ? 0 : ChronoUnit.DAYS.between(sortedHistory.get(0).getSnapshotTimestamp(), LocalDateTime.now()),
            current.getRowCount(),
            current.getSizeBytes() / (1024.0 * 1024.0 * 1024.0));

        return GrowthPrediction.builder()
            .tableName(tableName)
            .growthCategory(growthCategory)
            .monthlyGrowthRate(BigDecimal.valueOf(metrics.getMonthlyGrowthRate()).setScale(2, RoundingMode.HALF_UP))
            .dailyGrowthRate(BigDecimal.valueOf(metrics.getDailyGrowthRate()).setScale(4, RoundingMode.HALF_UP))
            .currentSizeBytes(current.getSizeBytes())
            .currentRowCount(current.getRowCount())
            .predictedSize30Days(size30Days)
            .predictedSize90Days(size90Days)
            .predictedSize365Days(size365Days)
            .predictedRows30Days(rows30Days)
            .predictedRows90Days(rows90Days)
            .predictedRows365Days(rows365Days)
            .daysToWarningThreshold(daysToWarning)
            .daysToCriticalThreshold(daysToCritical)
            .daysToRowWarningThreshold(daysToRowWarning)
            .daysToRowCriticalThreshold(daysToRowCritical)
            .historicalDataPoints(sortedHistory.size())
            .confidence(BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP))
            .alerts(alerts)
            .recommendations(recommendations)
            .reasoning(reasoning)
            .build();
    }

    private GrowthMetrics calculateGrowthMetrics(CurrentTableStats current, List<TableStatsHistory> history) {
        if (history.isEmpty() || history.size() < 2) {
            return GrowthMetrics.builder()
                .dailyGrowthRate(0)
                .monthlyGrowthRate(0)
                .dailyRowGrowthRate(0)
                .build();
        }

        // Use linear regression for more accurate growth rate
        TableStatsHistory first = history.get(0);
        TableStatsHistory last = history.get(history.size() - 1);

        long daysBetween = ChronoUnit.DAYS.between(first.getSnapshotTimestamp(), last.getSnapshotTimestamp());
        if (daysBetween <= 0) daysBetween = 1;

        // Size growth rate
        long sizeChange = current.getSizeBytes() - (first.getSizeBytes() != null ? first.getSizeBytes() : 0);
        double dailySizeGrowth = (double) sizeChange / daysBetween;
        double monthlySizeGrowthPct = first.getSizeBytes() != null && first.getSizeBytes() > 0
            ? (dailySizeGrowth * 30 / first.getSizeBytes()) * 100
            : 0;

        // Row growth rate
        long rowChange = current.getRowCount() - (first.getRowCount() != null ? first.getRowCount() : 0);
        double dailyRowGrowth = (double) rowChange / daysBetween;

        return GrowthMetrics.builder()
            .dailyGrowthRate(dailySizeGrowth)
            .monthlyGrowthRate(monthlySizeGrowthPct)
            .dailyRowGrowthRate(dailyRowGrowth)
            .build();
    }

    private String categorizeGrowth(double monthlyGrowthRate) {
        if (monthlyGrowthRate > 50) return "EXPLOSIVE";
        if (monthlyGrowthRate > 20) return "RAPID";
        if (monthlyGrowthRate > 5) return "STEADY";
        if (monthlyGrowthRate > 1) return "SLOW";
        if (monthlyGrowthRate > -1) return "STABLE";
        return "SHRINKING";
    }

    private long predictSize(long currentSize, double dailyGrowthRate, int days) {
        return currentSize + (long) (dailyGrowthRate * days);
    }

    private long predictRows(long currentRows, double dailyGrowthRate, int days) {
        return currentRows + (long) (dailyGrowthRate * days);
    }

    private Integer calculateDaysToThreshold(long currentSize, long threshold, double dailyGrowthRate) {
        if (dailyGrowthRate <= 0) return null;
        long remaining = threshold - currentSize;
        return (int) Math.ceil(remaining / dailyGrowthRate);
    }

    private Integer calculateDaysToRowThreshold(long currentRows, long threshold, double dailyGrowthRate) {
        if (dailyGrowthRate <= 0) return null;
        long remaining = threshold - currentRows;
        return (int) Math.ceil(remaining / dailyGrowthRate);
    }

    private double calculateConfidence(int historyPoints) {
        // More history = higher confidence
        if (historyPoints >= 30) return 90;
        if (historyPoints >= 14) return 75;
        if (historyPoints >= 7) return 60;
        if (historyPoints >= 3) return 40;
        return 20;
    }

    @Data
    @Builder
    private static class CurrentTableStats {
        private Long rowCount;
        private Long sizeBytes;
    }

    @Data
    @Builder
    private static class GrowthMetrics {
        private double dailyGrowthRate;      // bytes per day
        private double monthlyGrowthRate;    // percentage
        private double dailyRowGrowthRate;   // rows per day
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GrowthPrediction {
        private String tableName;
        private String growthCategory;  // EXPLOSIVE, RAPID, STEADY, SLOW, STABLE, SHRINKING
        private BigDecimal monthlyGrowthRate;
        private BigDecimal dailyGrowthRate;
        private Long currentSizeBytes;
        private Long currentRowCount;
        private Long predictedSize30Days;
        private Long predictedSize90Days;
        private Long predictedSize365Days;
        private Long predictedRows30Days;
        private Long predictedRows90Days;
        private Long predictedRows365Days;
        private Integer daysToWarningThreshold;
        private Integer daysToCriticalThreshold;
        private Integer daysToRowWarningThreshold;
        private Integer daysToRowCriticalThreshold;
        private Integer historicalDataPoints;
        private BigDecimal confidence;
        private List<String> alerts;
        private List<String> recommendations;
        private String reasoning;
    }
}
