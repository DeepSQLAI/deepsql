package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Brain 2.0: Column Statistics
 *
 * Independent cardinality estimation data using statistical sketches.
 * Inspired by optd-gungnir's approach using TDigest and HyperLogLog.
 *
 * Used to provide better cardinality estimates than the database optimizer,
 * which often has stale or inaccurate statistics.
 */
@Entity
@Table(name = "column_statistics", indexes = {
    @Index(name = "idx_col_stats_conn_table", columnList = "connectionId,tableName")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnStatistics {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String tableName;

    @Column(nullable = false)
    private String columnName;

    /**
     * Data type of the column.
     */
    @Column(length = 100)
    private String dataType;

    // Basic statistics
    @Column
    private Long rowCount;

    @Column
    private Long distinctCount;

    @Column
    private Long nullCount;

    @Column
    private Double nullFraction;

    /**
     * TDigest for distribution estimation (serialized, base64 encoded).
     * Allows estimation of quantiles and percentiles.
     */
    @Column(columnDefinition = "TEXT")
    private String tdigestData;

    /**
     * HyperLogLog for precise distinct count estimation (serialized).
     */
    @Column(columnDefinition = "TEXT")
    private String hllData;

    /**
     * Most Common Values (MCV) - common values for skewed distributions.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> mcvValues;

    /**
     * Frequencies of MCVs (0-1 fraction).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Double> mcvFrequencies;

    // Range statistics for numeric columns
    @Column(columnDefinition = "TEXT")
    private String minValue;

    @Column(columnDefinition = "TEXT")
    private String maxValue;

    @Column
    private Double avgValue;

    @Column
    private Double stdDeviation;

    /**
     * Histogram bounds for complex distributions.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> histogramBounds;

    /**
     * Count per histogram bucket.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Long> histogramCounts;

    /**
     * Average string length (for text columns).
     */
    @Column
    private Double avgLength;

    /**
     * Correlation with other columns.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> correlationData;

    /**
     * Sample size used for statistics collection.
     */
    @Column
    private Integer sampleSize;

    @Column(nullable = false)
    private LocalDateTime collectedAt;

    /**
     * Method used for collection.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    @Builder.Default
    private CollectionMethod collectionMethod = CollectionMethod.SAMPLE;

    @PrePersist
    protected void onCreate() {
        if (collectedAt == null) {
            collectedAt = LocalDateTime.now();
        }
    }

    /**
     * Estimate cardinality for equality predicate.
     */
    public long estimateEquality(String value) {
        if (rowCount == null || rowCount == 0) {
            return 0;
        }

        // Check if it's a MCV
        if (mcvValues != null && mcvFrequencies != null) {
            int idx = mcvValues.indexOf(value);
            if (idx >= 0 && idx < mcvFrequencies.size()) {
                return Math.round(rowCount * mcvFrequencies.get(idx));
            }
        }

        // Default: assume uniform distribution among non-MCV values
        if (distinctCount != null && distinctCount > 0) {
            double mcvFraction = 0;
            if (mcvFrequencies != null) {
                mcvFraction = mcvFrequencies.stream().mapToDouble(Double::doubleValue).sum();
            }
            double remainingFraction = 1.0 - mcvFraction;
            long mcvCount = mcvValues != null ? mcvValues.size() : 0;
            long remainingDistinct = distinctCount - mcvCount;

            if (remainingDistinct > 0) {
                return Math.round(rowCount * remainingFraction / remainingDistinct);
            }
        }

        // Fallback: 1/distinctCount
        if (distinctCount != null && distinctCount > 0) {
            return Math.max(1, rowCount / distinctCount);
        }

        return 1;
    }

    /**
     * Estimate cardinality for range predicate.
     */
    public long estimateRange(String lowBound, String highBound) {
        if (rowCount == null || rowCount == 0) {
            return 0;
        }

        // Use histogram if available
        if (histogramBounds != null && histogramCounts != null && !histogramBounds.isEmpty()) {
            return estimateRangeFromHistogram(lowBound, highBound);
        }

        // Use min/max range estimation
        if (minValue != null && maxValue != null) {
            try {
                double min = Double.parseDouble(minValue);
                double max = Double.parseDouble(maxValue);
                double range = max - min;

                if (range > 0) {
                    double low = lowBound != null ? Double.parseDouble(lowBound) : min;
                    double high = highBound != null ? Double.parseDouble(highBound) : max;

                    low = Math.max(low, min);
                    high = Math.min(high, max);

                    double selectivity = (high - low) / range;
                    return Math.max(1, Math.round(rowCount * selectivity));
                }
            } catch (NumberFormatException e) {
                // Not numeric, use default
            }
        }

        // Default: 30% selectivity for range queries
        return Math.max(1, Math.round(rowCount * 0.3));
    }

    private long estimateRangeFromHistogram(String lowBound, String highBound) {
        // Simplified histogram estimation
        // In production, would need proper bucket identification
        long total = 0;
        int bucketCount = histogramBounds.size() - 1;

        for (int i = 0; i < bucketCount && i < histogramCounts.size(); i++) {
            // Check if bucket overlaps with range
            // Simplified: count all buckets
            total += histogramCounts.get(i);
        }

        return Math.max(1, total);
    }

    public enum CollectionMethod {
        SAMPLE,     // Random sample of rows
        FULL_SCAN,  // Full table scan
        INCREMENTAL // Incremental update from previous stats
    }
}
