package com.dbaagent.service.brain.analysis;

import com.dbaagent.model.QueryAntiPattern;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.QueryAntiPatternRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for detecting query quality anti-patterns.
 * Implements BRAIN-design.md Section 8: "Query Quality Analysis"
 *
 * Detects:
 * - Functions on filter columns (e.g., DATE(created_at) = ...)
 * - SELECT * in analytical queries
 * - DISTINCT masking JOIN errors
 * - JOINs without predicates (Cartesian products)
 * - High rows-examined / rows-returned ratio
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QueryQualityAnalysisService {

    private final QueryAntiPatternRepository queryAntiPatternRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final ObjectMapper objectMapper;

    @Value("${slow-query.history.lookback-days:90}")
    private int historyLookbackDays;

    @Value("${slow-query.history.max-batch:1000}")
    private int historyBatchSize;

    // Regex patterns for detection
    private static final Pattern FUNCTION_ON_WHERE = Pattern.compile(
        "WHERE\\s+\\w+\\s*\\([^)]*\\w+\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_STAR = Pattern.compile(
        "SELECT\\s+\\*\\s+FROM", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTINCT_PATTERN = Pattern.compile(
        "SELECT\\s+DISTINCT", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_PATTERN = Pattern.compile(
        "JOIN\\s+\\w+(?:\\s+\\w+)?\\s+ON", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_WITHOUT_ON = Pattern.compile(
        "FROM\\s+\\w+\\s*,\\s*\\w+", Pattern.CASE_INSENSITIVE);

    /**
     * Analyze query quality for a connection.
     */
    public void analyzeQueryQuality(String connectionId) {
        log.info("Analyzing query quality for connection: {}", connectionId);

        int patternsDetected = 0;
        LocalDateTime since = LocalDateTime.now().minusDays(historyLookbackDays);
        int page = 0;
        boolean any = false;

        while (true) {
            List<SlowQueryHistory> slowQueries = slowQueryHistoryRepository
                .findByConnectionIdSince(connectionId, since, PageRequest.of(page, historyBatchSize));

            if (slowQueries.isEmpty()) {
                break;
            }
            any = true;

            for (SlowQueryHistory slowQuery : slowQueries) {
                try {
                    List<QuerySample> samples = extractQuerySamples(slowQuery.getAnalysisData());
                    if (samples.isEmpty()) {
                        continue;
                    }

                    for (QuerySample sample : samples) {
                        if (sample.sqlText == null || sample.sqlText.isBlank()) {
                            continue;
                        }
                        patternsDetected += detectAntiPatterns(
                            connectionId,
                            sample.sqlText,
                            sample.rowsExamined,
                            sample.rowsReturned,
                            sample.executionTimeMs
                        );
                    }
                } catch (Exception e) {
                    log.debug("Could not parse slow query history {}: {}", slowQuery.getId(), e.getMessage());
                }
            }

            if (slowQueries.size() < historyBatchSize) {
                break;
            }
            page++;
        }

        if (!any) {
            log.info("No slow queries found for analysis");
            return;
        }

        log.info("Detected {} query anti-patterns", patternsDetected);
    }

    /**
     * Detect anti-patterns in a single query.
     */
    private int detectAntiPatterns(String connectionId, String sqlText, long rowsExamined,
                                    long rowsReturned, long executionTime) {
        int detected = 0;
        String fingerprint = normalizeQuery(sqlText);

        // Pattern 1: Functions on filter columns
        if (FUNCTION_ON_WHERE.matcher(sqlText).find()) {
            saveOrUpdateAntiPattern(
                connectionId,
                fingerprint,
                "FUNCTION_ON_FILTER",
                QueryAntiPattern.Severity.HIGH,
                "Function call on indexed column in WHERE clause",
                "Query uses functions on columns in WHERE clause (e.g., DATE(created_at) = ...). " +
                "This prevents index usage and forces full table scan.",
                "Remove function from WHERE clause. Use range queries instead: " +
                "Instead of WHERE DATE(created_at) = '2024-01-01', " +
                "use WHERE created_at >= '2024-01-01' AND created_at < '2024-01-02'",
                sqlText,
                rowsExamined,
                rowsReturned,
                executionTime
            );
            detected++;
        }

        // Pattern 2: SELECT * in large queries
        if (SELECT_STAR.matcher(sqlText).find() && rowsReturned > 100) {
            saveOrUpdateAntiPattern(
                connectionId,
                fingerprint,
                "SELECT_STAR",
                QueryAntiPattern.Severity.MEDIUM,
                "SELECT * returns unnecessary columns",
                String.format("Query uses SELECT * and returns %,d rows, potentially transferring " +
                    "unnecessary data. This wastes network bandwidth and memory.", rowsReturned),
                "Specify only required columns in SELECT clause. Replace SELECT * with explicit " +
                "column list to reduce data transfer and improve performance.",
                sqlText,
                rowsExamined,
                rowsReturned,
                executionTime
            );
            detected++;
        }

        // Pattern 3: DISTINCT possibly masking JOIN errors
        if (DISTINCT_PATTERN.matcher(sqlText).find() && JOIN_PATTERN.matcher(sqlText).find()) {
            saveOrUpdateAntiPattern(
                connectionId,
                fingerprint,
                "DISTINCT_ABUSE",
                QueryAntiPattern.Severity.MEDIUM,
                "DISTINCT used with JOIN may mask data quality issues",
                "Query uses DISTINCT with JOINs, which may indicate incorrect JOIN conditions " +
                "causing duplicate rows. DISTINCT hides the problem but doesn't fix it.",
                "Review JOIN conditions to ensure correctness. If duplicates are expected, " +
                "document why. Consider using GROUP BY instead if aggregation is intended.",
                sqlText,
                rowsExamined,
                rowsReturned,
                executionTime
            );
            detected++;
        }

        // Pattern 4: JOIN without ON clause (Cartesian product)
        if (JOIN_WITHOUT_ON.matcher(sqlText).find()) {
            saveOrUpdateAntiPattern(
                connectionId,
                fingerprint,
                "JOIN_WITHOUT_PREDICATE",
                QueryAntiPattern.Severity.CRITICAL,
                "Cartesian product detected (JOIN without ON clause)",
                "Query uses comma-separated tables in FROM clause without JOIN conditions, " +
                "creating a Cartesian product. This can cause exponential row growth.",
                "Add explicit JOIN conditions with ON clause. Convert implicit joins (comma-separated) " +
                "to explicit JOINs with proper ON predicates.",
                sqlText,
                rowsExamined,
                rowsReturned,
                executionTime
            );
            detected++;
        }

        // Pattern 5: High examination ratio
        if (rowsExamined > 0 && rowsReturned > 0) {
            double ratio = (double) rowsExamined / rowsReturned;
            if (ratio > 100) {  // Examining 100x more rows than returning
                saveOrUpdateAntiPattern(
                    connectionId,
                    fingerprint,
                    "HIGH_EXAMINATION_RATIO",
                    ratio > 1000 ? QueryAntiPattern.Severity.CRITICAL : QueryAntiPattern.Severity.HIGH,
                    String.format("Inefficient query: examining %.0fx more rows than returning", ratio),
                    String.format("Query examines %,d rows but returns only %,d (%.0f:1 ratio). " +
                        "This indicates missing or ineffective indexes.", rowsExamined, rowsReturned, ratio),
                    "Add indexes on filter columns. Review WHERE clause conditions and ensure " +
                    "appropriate indexes exist. Consider composite indexes for multiple filter columns.",
                    sqlText,
                    rowsExamined,
                    rowsReturned,
                    executionTime
                );
                detected++;
            }
        }

        // Pattern 6: Subquery in SELECT list (correlated subquery)
        if (sqlText.toUpperCase().contains("SELECT") &&
            Pattern.compile("SELECT[^,()]+\\([^)]*SELECT", Pattern.CASE_INSENSITIVE).matcher(sqlText).find()) {
            saveOrUpdateAntiPattern(
                connectionId,
                fingerprint,
                "SUBQUERY_IN_SELECT",
                QueryAntiPattern.Severity.HIGH,
                "Correlated subquery in SELECT list",
                "Query has subquery in SELECT list that executes once per row, causing N+1 query problem. " +
                "This can be extremely slow for large result sets.",
                "Rewrite using JOINs or window functions. Move subquery to FROM clause as derived table, " +
                "or use LEFT JOIN with aggregation.",
                sqlText,
                rowsExamined,
                rowsReturned,
                executionTime
            );
            detected++;
        }

        return detected;
    }

    /**
     * Save or update anti-pattern occurrence.
     */
    private void saveOrUpdateAntiPattern(String connectionId, String fingerprint, String patternType,
                                          QueryAntiPattern.Severity severity, String title,
                                          String description, String recommendation, String exampleQuery,
                                          long rowsExamined, long rowsReturned, long executionTime) {
        Optional<QueryAntiPattern> existing = queryAntiPatternRepository
            .findByConnectionIdAndQueryFingerprintAndPatternType(connectionId, fingerprint, patternType);

        if (existing.isPresent()) {
            // Update existing
            QueryAntiPattern pattern = existing.get();
            pattern.setOccurrences(pattern.getOccurrences() + 1);
            pattern.setTotalExecutionTimeMs(pattern.getTotalExecutionTimeMs() + executionTime);
            pattern.setAvgExecutionTimeMs(pattern.getTotalExecutionTimeMs() / pattern.getOccurrences());
            pattern.setLastDetectedAt(LocalDateTime.now());

            queryAntiPatternRepository.save(pattern);
            log.debug("Updated anti-pattern: {} (occurrences: {})", patternType, pattern.getOccurrences());
        } else {
            // Create new
            BigDecimal ratio = null;
            if (rowsReturned > 0) {
                ratio = BigDecimal.valueOf((double) rowsExamined / rowsReturned)
                    .setScale(2, RoundingMode.HALF_UP);
            }

            QueryAntiPattern pattern = QueryAntiPattern.builder()
                .connectionId(connectionId)
                .queryFingerprint(fingerprint)
                .queryText(truncate(exampleQuery, 5000))
                .patternType(patternType)
                .severity(severity)
                .occurrences(1)
                .avgExecutionTimeMs(executionTime)
                .totalExecutionTimeMs(executionTime)
                .rowsExamined(rowsExamined)
                .rowsReturned(rowsReturned)
                .examinationRatio(ratio)
                .title(title)
                .description(description)
                .recommendation(recommendation)
                .exampleQuery(truncate(exampleQuery, 5000))
                .build();

            queryAntiPatternRepository.save(pattern);
            log.debug("Detected new anti-pattern: {}", patternType);
        }
    }

    /**
     * Normalize query to fingerprint (remove literals).
     */
    private String normalizeQuery(String sql) {
        // Remove string literals
        String normalized = sql.replaceAll("'[^']*'", "?");
        // Remove numbers
        normalized = normalized.replaceAll("\\b\\d+\\b", "?");
        // Remove whitespace variations
        normalized = normalized.replaceAll("\\s+", " ").trim();

        return normalized;
    }

    /**
     * Truncate string to max length.
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    /**
     * Get query anti-patterns for a connection.
     */
    public List<QueryAntiPattern> getQueryAntiPatterns(String connectionId, int limit) {
        return queryAntiPatternRepository.findTopByConnectionIdOrderByTotalExecutionTimeMs(
            connectionId, limit);
    }

    /**
     * Get all query anti-patterns for a connection.
     */
    public List<QueryAntiPattern> getAllQueryAntiPatterns(String connectionId) {
        return queryAntiPatternRepository.findByConnectionIdOrderByTotalExecutionTimeMsDesc(connectionId);
    }

    private record QuerySample(String sqlText, long rowsExamined, long rowsReturned, long executionTimeMs) {}

    private List<QuerySample> extractQuerySamples(String analysisData) {
        if (analysisData == null || analysisData.trim().isEmpty()) {
            return List.of();
        }

        List<QuerySample> samples = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(analysisData);
            List<JsonNode> queryNodes = new ArrayList<>();

            if (root.has("topSlowQueries") && root.get("topSlowQueries").isArray()) {
                root.get("topSlowQueries").forEach(queryNodes::add);
            }
            if (root.has("slowQueries") && root.get("slowQueries").isArray()) {
                root.get("slowQueries").forEach(queryNodes::add);
            }
            if (root.has("queries") && root.get("queries").isArray()) {
                root.get("queries").forEach(queryNodes::add);
            }
            if (queryNodes.isEmpty() && root.isArray()) {
                root.forEach(queryNodes::add);
            }

            for (JsonNode queryNode : queryNodes) {
                String sqlText = readFirstString(queryNode,
                    "queryText", "sql", "query", "normalizedQuery", "statement");
                long rowsExamined = readFirstLong(queryNode,
                    "rowsExamined", "rows_examined", "avgRowsExamined", "rowsExaminedTotal");
                long rowsReturned = readFirstLong(queryNode,
                    "rowsSent", "rows_sent", "rowsReturned", "rows_returned", "avgRowsSent", "avgRowsReturned");
                long executionTime = readExecutionTimeMs(queryNode);

                samples.add(new QuerySample(sqlText, rowsExamined, rowsReturned, executionTime));
            }
        } catch (Exception e) {
            log.debug("Failed to parse analysis data JSON: {}", e.getMessage());
        }

        return samples;
    }

    private String readFirstString(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                String value = node.get(field).asText();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return null;
    }

    private long readFirstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field) && node.get(field).isNumber()) {
                return node.get(field).asLong();
            }
        }
        return 0;
    }

    private long readExecutionTimeMs(JsonNode node) {
        if (node.has("avgExecutionTimeMs") && node.get("avgExecutionTimeMs").isNumber()) {
            return node.get("avgExecutionTimeMs").asLong();
        }
        if (node.has("maxExecutionTimeMs") && node.get("maxExecutionTimeMs").isNumber()) {
            return node.get("maxExecutionTimeMs").asLong();
        }
        if (node.has("executionTimeMs") && node.get("executionTimeMs").isNumber()) {
            return node.get("executionTimeMs").asLong();
        }
        if (node.has("duration_ms") && node.get("duration_ms").isNumber()) {
            return node.get("duration_ms").asLong();
        }
        if (node.has("query_time") && node.get("query_time").isNumber()) {
            double raw = node.get("query_time").asDouble();
            if (raw < 1000) {
                return Math.round(raw * 1000);
            }
            return Math.round(raw);
        }
        if (node.has("duration") && node.get("duration").isNumber()) {
            double raw = node.get("duration").asDouble();
            if (raw < 1000) {
                return Math.round(raw * 1000);
            }
            return Math.round(raw);
        }
        return 0;
    }
}
