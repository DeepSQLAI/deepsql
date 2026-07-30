package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyColumnScore {
    private String tableName;
    private String columnName;
    private String dataType;
    private BigDecimal importanceScore;
    private BigDecimal enhancedImportanceScore;

    // Usage breakdown
    private UsageBreakdown usageBreakdown;

    // Cardinality info
    private Long distinctCount;
    private Long totalRows;
    private BigDecimal selectivity;
    private BigDecimal cardinalityRatio;
    private BigDecimal nullRatio;
    private String cardinalityCategory; // HIGH, MEDIUM, LOW

    // Data skew analysis (BRAIN-design.md Section 3.2)
    private Double skewCoefficient;  // 0.0 (uniform) to 1.0 (highly skewed)
    private Boolean isHeavilySkewed;  // True if skew > 0.7
    private String skewCategory;  // LOW, MEDIUM, HIGH, EXTREME
    private List<TopValueInfo> topValues;  // Top frequent values with percentages

    // Key classification (BRAIN-design.md Section 7)
    private String keyType;  // TRUE_KEY, ACCIDENTAL_KEY, SURROGATE_KEY, NON_KEY
    private BigDecimal keyConfidence;  // 0.0 to 1.0 confidence score

    // Partitioning candidates (BRAIN-design.md Section 7)
    private Boolean isPartitionCandidate;
    private String partitioningType;  // RANGE, LIST, HASH
    private BigDecimal partitioningScore;  // 0-100 score
    private String partitioningRecommendation;

    // Enhanced scoring metrics
    private BigDecimal frequencyScore;
    private BigDecimal recencyScore;
    private BigDecimal mlPredictionScore;
    private BigDecimal usesPerDay;

    // Index status
    private Boolean isIndexed;
    private List<String> indexNames;
    private String indexName;
    private Long indexUsageCount;
    private Long indexScanCount;
    private Boolean hasUnusedIndex;

    // Anti-patterns
    private Boolean hasAntiPatterns;
    private List<AntiPatternSummary> antiPatterns;

    // Composite index recommendations
    private List<String> compositeIndexRecommendations;
}
