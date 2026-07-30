package com.dbaagent.service;

import com.dbaagent.model.ColumnProfile;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for analyzing data skew and calculating top-N value distributions.
 * Implements BRAIN-design.md Section 3.2: "Data skew (top-N values)"
 *
 * Skew Coefficient Calculation:
 * - 0.0 = Perfectly uniform distribution
 * - 0.5 = Moderate skew
 * - 1.0 = Extremely skewed (single value dominates)
 *
 * Formula: skew = (topValueCount / totalRows)
 * - If top value is 100% of data → skew = 1.0
 * - If top value is 50% of data → skew = 0.5
 * - If top value is 10% of data → skew = 0.1
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SkewAnalysisService {

    private final QueryExecutorService queryExecutorService;
    private final ObjectMapper objectMapper;

    /**
     * Calculates top-N values and skew coefficient for a column.
     *
     * @param connectionId Database connection ID
     * @param tableName Table name
     * @param columnName Column name
     * @param topN Number of top values to track (default 10)
     * @return SkewAnalysisResult containing top values and skew coefficient
     */
    public SkewAnalysisResult analyzeColumnSkew(String connectionId, String tableName,
                                                String columnName, int topN) {
        try {
            log.debug("Analyzing skew for {}.{} (top {})", tableName, columnName, topN);

            // Query to get top N values with counts
            String sql = String.format(
                "SELECT %s as value, COUNT(*) as count " +
                "FROM %s " +
                "WHERE %s IS NOT NULL " +
                "GROUP BY %s " +
                "ORDER BY count DESC " +
                "LIMIT %d",
                columnName, tableName, columnName, columnName, topN
            );

            // Query to get total non-NULL rows
            String totalSql = String.format(
                "SELECT COUNT(*) as total FROM %s WHERE %s IS NOT NULL",
                tableName, columnName
            );

            // Execute queries
            QueryResult topValuesResult = queryExecutorService.executeQuery(connectionId,
                new QueryRequest(sql, topN, null));
            QueryResult totalResult = queryExecutorService.executeQuery(connectionId,
                new QueryRequest(totalSql, null, null));

            if (topValuesResult.getRows() == null || topValuesResult.getRows().isEmpty() ||
                totalResult.getRows() == null || totalResult.getRows().isEmpty()) {
                return SkewAnalysisResult.builder()
                    .topValues(new ArrayList<>())
                    .skewCoefficient(0.0)
                    .build();
            }

            // Get total rows from first result
            long totalRows = ((Number) totalResult.getRows().get(0).get(0)).longValue();

            // Build top values list with percentages
            List<TopValue> topValues = new ArrayList<>();
            long topValueCount = 0;

            // Process each row from topValuesResult
            // Format: [value, count]
            for (List<Object> row : topValuesResult.getRows()) {
                Object value = row.get(0);  // First column: value
                long count = ((Number) row.get(1)).longValue();  // Second column: count

                if (topValueCount == 0) {
                    topValueCount = count; // Track the most frequent value
                }

                double percentage = totalRows > 0 ? (count * 100.0) / totalRows : 0.0;

                topValues.add(TopValue.builder()
                    .value(value != null ? value.toString() : "NULL")
                    .count(count)
                    .percentage(BigDecimal.valueOf(percentage).setScale(2, RoundingMode.HALF_UP))
                    .build());
            }

            // Calculate skew coefficient
            // Formula: (most frequent value count) / (total rows)
            double skewCoefficient = totalRows > 0 ? (double) topValueCount / totalRows : 0.0;

            return SkewAnalysisResult.builder()
                .topValues(topValues)
                .skewCoefficient(skewCoefficient)
                .totalNonNullRows(totalRows)
                .build();

        } catch (Exception e) {
            log.error("Failed to analyze skew for {}.{}: {}", tableName, columnName, e.getMessage(), e);
            return SkewAnalysisResult.builder()
                .topValues(new ArrayList<>())
                .skewCoefficient(0.0)
                .build();
        }
    }

    /**
     * Enriches a ColumnProfile with skew analysis data.
     *
     * @param connectionId Database connection ID
     * @param profile Column profile to enrich
     */
    public void enrichColumnProfileWithSkew(String connectionId, ColumnProfile profile) {
        try {
            SkewAnalysisResult result = analyzeColumnSkew(
                connectionId,
                profile.getTableName(),
                profile.getColumnName(),
                10 // Top 10 values
            );

            // Serialize top values to JSON
            String topValuesJson = objectMapper.writeValueAsString(result.getTopValues());
            profile.setTopValues(topValuesJson);
            profile.setSkewCoefficient(result.getSkewCoefficient());

            log.debug("Enriched {}.{} with skew coefficient: {}",
                profile.getTableName(), profile.getColumnName(), result.getSkewCoefficient());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize top values for {}.{}: {}",
                profile.getTableName(), profile.getColumnName(), e.getMessage());
        } catch (Exception e) {
            log.error("Failed to enrich column profile with skew: {}", e.getMessage());
        }
    }

    /**
     * Parses top values JSON from ColumnProfile.
     *
     * @param topValuesJson JSON string from column_profile.top_values
     * @return List of TopValue objects
     */
    public List<TopValue> parseTopValues(String topValuesJson) {
        if (topValuesJson == null || topValuesJson.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(topValuesJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, TopValue.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to parse top values JSON: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Categorizes skew level based on coefficient.
     *
     * @param skewCoefficient Skew coefficient (0.0 to 1.0)
     * @return Skew category: LOW, MEDIUM, HIGH, or EXTREME
     */
    public String categorizeSkew(double skewCoefficient) {
        if (skewCoefficient >= 0.7) {
            return "EXTREME"; // Top value is 70%+ of data
        } else if (skewCoefficient >= 0.5) {
            return "HIGH"; // Top value is 50-70% of data
        } else if (skewCoefficient >= 0.3) {
            return "MEDIUM"; // Top value is 30-50% of data
        } else {
            return "LOW"; // Top value is <30% of data
        }
    }

    /**
     * Result of skew analysis containing top values and skew coefficient.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkewAnalysisResult {
        private List<TopValue> topValues;
        private double skewCoefficient;
        private long totalNonNullRows;
    }

    /**
     * Represents a top frequent value with count and percentage.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopValue {
        private String value;
        private long count;
        private BigDecimal percentage;
    }
}
