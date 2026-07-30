package com.dbaagent.service.brain.classification;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for classifying query complexity per table.
 *
 * Complexity Levels:
 * - SIMPLE_LOOKUP: Primary key/index lookups only
 * - RANGE_SCAN: Range queries, pagination, BETWEEN
 * - COMPLEX_JOIN: Multi-table joins (3+ tables)
 * - AGGREGATION_HEAVY: GROUP BY, window functions, DISTINCT
 * - FULL_SCAN: Sequential scans, no index usage
 * - MIXED: Combination of different query types
 *
 * Uses slow query logs and query patterns to determine complexity.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QueryComplexityClassificationService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${slow-query.history.lookback-days:90}")
    private int historyLookbackDays;

    @Value("${slow-query.history.max-batch:1000}")
    private int historyBatchSize;

    // Query pattern detection
    private static final Pattern JOIN_PATTERN = Pattern.compile(
        "(?i)\\b(JOIN|INNER JOIN|LEFT JOIN|RIGHT JOIN|OUTER JOIN|CROSS JOIN)\\b");
    private static final Pattern AGGREGATION_PATTERN = Pattern.compile(
        "(?i)\\b(GROUP BY|SUM\\(|COUNT\\(|AVG\\(|MAX\\(|MIN\\(|HAVING|DISTINCT|WINDOW|OVER\\()");
    private static final Pattern RANGE_PATTERN = Pattern.compile(
        "(?i)\\b(BETWEEN|>=|<=|>|<|LIMIT|OFFSET|ORDER BY)\\b");
    private static final Pattern SUBQUERY_PATTERN = Pattern.compile(
        "(?i)\\(\\s*SELECT");
    private static final Pattern TABLE_PATTERN = Pattern.compile(
        "(?i)\\bFROM\\s+([`\"\\[]?\\w+[`\"\\]]?)");

    /**
     * Classify query complexity for all tables in a connection.
     */
    public Map<String, QueryComplexity> classifyQueryComplexity(String connectionId) {
        Map<String, QueryComplexity> results = new HashMap<>();
        Map<String, List<QueryPattern>> tablePatterns = new HashMap<>();

        // Get query patterns from slow query history (paged)
        LocalDateTime since = LocalDateTime.now().minusDays(historyLookbackDays);
        int page = 0;
        while (true) {
            List<SlowQueryHistory> histories = slowQueryHistoryRepository
                .findByConnectionIdSince(connectionId, since, PageRequest.of(page, historyBatchSize));
            if (histories.isEmpty()) {
                break;
            }

            for (SlowQueryHistory history : histories) {
                if (history.getAnalysisData() != null) {
                    analyzeQueriesFromHistory(history, tablePatterns);
                }
            }

            if (histories.size() < historyBatchSize) {
                break;
            }
            page++;
        }

        // Also analyze from database statistics
        analyzeFromDatabaseStats(connectionId, tablePatterns);

        // Calculate complexity for each table
        for (Map.Entry<String, List<QueryPattern>> entry : tablePatterns.entrySet()) {
            String tableName = entry.getKey();
            List<QueryPattern> patterns = entry.getValue();
            results.put(tableName.toLowerCase(), calculateComplexity(tableName, patterns));
        }

        return results;
    }

    private void analyzeQueriesFromHistory(SlowQueryHistory history, Map<String, List<QueryPattern>> tablePatterns) {
        try {
            String analysisDataStr = history.getAnalysisData();
            if (analysisDataStr == null || analysisDataStr.isBlank()) return;

            Map<String, Object> analysisData = objectMapper.readValue(
                analysisDataStr, new TypeReference<Map<String, Object>>() {});
            if (analysisData == null) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topSlowQueries = (List<Map<String, Object>>) analysisData.get("topSlowQueries");
            if (topSlowQueries == null) return;

            for (Map<String, Object> query : topSlowQueries) {
                String queryText = (String) query.get("queryText");
                if (queryText == null) continue;

                QueryPattern pattern = analyzeQueryPattern(queryText);

                // Extract tables from query
                Set<String> tables = extractTables(queryText);
                for (String table : tables) {
                    tablePatterns.computeIfAbsent(table.toLowerCase(), k -> new ArrayList<>()).add(pattern);
                }
            }
        } catch (Exception e) {
            log.debug("Error analyzing query history: {}", e.getMessage());
        }
    }

    private void analyzeFromDatabaseStats(String connectionId, Map<String, List<QueryPattern>> tablePatterns) {
        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) return;

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
                analyzePostgresStats(conn, tablePatterns);
            } else if ("mysql".equalsIgnoreCase(dbType)) {
                analyzeMysqlStats(conn, tablePatterns);
            }
        } catch (Exception e) {
            log.debug("Error analyzing database stats: {}", e.getMessage());
        }
    }

    private void analyzePostgresStats(Connection conn, Map<String, List<QueryPattern>> tablePatterns) {
        String query = """
            SELECT
                relname as table_name,
                COALESCE(seq_scan, 0) as seq_scans,
                COALESCE(idx_scan, 0) as idx_scans,
                COALESCE(seq_tup_read, 0) as seq_tup_read,
                COALESCE(idx_tup_fetch, 0) as idx_tup_fetch
            FROM pg_stat_user_tables
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                long seqScans = rs.getLong("seq_scans");
                long idxScans = rs.getLong("idx_scans");

                // Create patterns based on scan types
                if (seqScans > 0) {
                    for (int i = 0; i < Math.min(seqScans, 10); i++) {
                        tablePatterns.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(QueryPattern.builder()
                                .patternType("FULL_SCAN")
                                .hasSeqScan(true)
                                .build());
                    }
                }
                if (idxScans > 0) {
                    for (int i = 0; i < Math.min(idxScans, 10); i++) {
                        tablePatterns.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(QueryPattern.builder()
                                .patternType("SIMPLE_LOOKUP")
                                .hasIndexScan(true)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error querying PostgreSQL stats: {}", e.getMessage());
        }
    }

    private void analyzeMysqlStats(Connection conn, Map<String, List<QueryPattern>> tablePatterns) {
        // For MySQL, we primarily rely on slow query log analysis
        // Add default patterns for tables without data
        String query = """
            SELECT TABLE_NAME as table_name
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            AND TABLE_TYPE = 'BASE TABLE'
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                if (!tablePatterns.containsKey(tableName)) {
                    tablePatterns.put(tableName, new ArrayList<>());
                }
            }
        } catch (Exception e) {
            log.debug("Error querying MySQL stats: {}", e.getMessage());
        }
    }

    private QueryPattern analyzeQueryPattern(String queryText) {
        int joinCount = countMatches(JOIN_PATTERN, queryText);
        boolean hasAggregation = AGGREGATION_PATTERN.matcher(queryText).find();
        boolean hasRange = RANGE_PATTERN.matcher(queryText).find();
        boolean hasSubquery = SUBQUERY_PATTERN.matcher(queryText).find();

        String patternType;
        int complexityScore;

        if (joinCount >= 3 || hasSubquery) {
            patternType = "COMPLEX_JOIN";
            complexityScore = 80 + Math.min(20, joinCount * 5);
        } else if (hasAggregation) {
            patternType = "AGGREGATION_HEAVY";
            complexityScore = 60 + (hasSubquery ? 20 : 0);
        } else if (joinCount > 0) {
            patternType = "COMPLEX_JOIN";
            complexityScore = 40 + joinCount * 15;
        } else if (hasRange) {
            patternType = "RANGE_SCAN";
            complexityScore = 30;
        } else {
            patternType = "SIMPLE_LOOKUP";
            complexityScore = 10;
        }

        return QueryPattern.builder()
            .patternType(patternType)
            .joinCount(joinCount)
            .hasAggregation(hasAggregation)
            .hasSubquery(hasSubquery)
            .hasRange(hasRange)
            .complexityScore(complexityScore)
            .build();
    }

    private Set<String> extractTables(String queryText) {
        Set<String> tables = new HashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(queryText);
        while (matcher.find()) {
            String table = matcher.group(1).replaceAll("[`\"\\[\\]]", "");
            tables.add(table);
        }
        return tables;
    }

    private int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private QueryComplexity calculateComplexity(String tableName, List<QueryPattern> patterns) {
        if (patterns.isEmpty()) {
            return QueryComplexity.builder()
                .tableName(tableName)
                .complexityLevel("UNKNOWN")
                .avgComplexityScore(BigDecimal.ZERO)
                .breakdown(Map.of())
                .queryCount(0)
                .reasoning("No query patterns available")
                .recommendations(List.of("Enable slow query logging to analyze query patterns"))
                .build();
        }

        Map<String, Integer> patternCounts = new HashMap<>();
        double totalComplexity = 0;

        for (QueryPattern pattern : patterns) {
            patternCounts.merge(pattern.getPatternType(), 1, Integer::sum);
            totalComplexity += pattern.getComplexityScore() != null ? pattern.getComplexityScore() : 0;
        }

        double avgComplexity = totalComplexity / patterns.size();

        // Calculate percentages
        Map<String, Double> breakdown = new HashMap<>();
        for (Map.Entry<String, Integer> entry : patternCounts.entrySet()) {
            double pct = (double) entry.getValue() / patterns.size() * 100;
            breakdown.put(entry.getKey().toLowerCase() + "_pct",
                BigDecimal.valueOf(pct).setScale(1, RoundingMode.HALF_UP).doubleValue());
        }

        // Determine dominant pattern
        String dominantPattern = patternCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("MIXED");

        String complexityLevel;
        List<String> recommendations = new ArrayList<>();

        if (avgComplexity >= 70 || "COMPLEX_JOIN".equals(dominantPattern)) {
            complexityLevel = "COMPLEX_JOIN";
            recommendations.add("Review query execution plans for optimization opportunities");
            recommendations.add("Consider denormalization for frequently joined tables");
            recommendations.add("Use materialized views for complex aggregations");
        } else if (avgComplexity >= 50 || "AGGREGATION_HEAVY".equals(dominantPattern)) {
            complexityLevel = "AGGREGATION_HEAVY";
            recommendations.add("Pre-compute aggregations where possible");
            recommendations.add("Consider summary tables or materialized views");
            recommendations.add("Partition large tables for better aggregation performance");
        } else if ("FULL_SCAN".equals(dominantPattern)) {
            complexityLevel = "FULL_SCAN";
            recommendations.add("Add indexes to support common query patterns");
            recommendations.add("Review WHERE clause conditions");
            recommendations.add("Consider covering indexes for frequent queries");
        } else if (avgComplexity >= 25 || "RANGE_SCAN".equals(dominantPattern)) {
            complexityLevel = "RANGE_SCAN";
            recommendations.add("Ensure range columns are indexed");
            recommendations.add("Consider composite indexes for range + equality conditions");
        } else {
            complexityLevel = "SIMPLE_LOOKUP";
            recommendations.add("Query pattern is optimal for caching");
            recommendations.add("Maintain index coverage for lookup columns");
        }

        String reasoning = String.format("Analyzed %d queries: %s dominant (%.0f%%), avg complexity %.1f",
            patterns.size(), dominantPattern,
            (double) patternCounts.getOrDefault(dominantPattern, 0) / patterns.size() * 100,
            avgComplexity);

        return QueryComplexity.builder()
            .tableName(tableName)
            .complexityLevel(complexityLevel)
            .avgComplexityScore(BigDecimal.valueOf(avgComplexity).setScale(2, RoundingMode.HALF_UP))
            .breakdown(breakdown)
            .queryCount(patterns.size())
            .dominantPattern(dominantPattern)
            .reasoning(reasoning)
            .recommendations(recommendations)
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryPattern {
        private String patternType;
        private Integer joinCount;
        private Boolean hasAggregation;
        private Boolean hasSubquery;
        private Boolean hasRange;
        private Boolean hasSeqScan;
        private Boolean hasIndexScan;
        private Integer complexityScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryComplexity {
        private String tableName;
        private String complexityLevel;  // SIMPLE_LOOKUP, RANGE_SCAN, COMPLEX_JOIN, AGGREGATION_HEAVY, FULL_SCAN, MIXED
        private BigDecimal avgComplexityScore;
        private Map<String, Double> breakdown;
        private Integer queryCount;
        private String dominantPattern;
        private String reasoning;
        private List<String> recommendations;
    }
}
