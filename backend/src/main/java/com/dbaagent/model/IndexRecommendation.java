package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for index recommendations generated during analysis
 * Used for inline recommendations in EXPLAIN plans and slow queries
 * NOT a JPA entity - see IndexRecommendationEntity for persistent recommendations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexRecommendation {

    private String id;
    private String connectionId;
    private String schemaName;
    private String tableName;
    private List<String> columns;
    private String indexType;  // BTREE, HASH, etc.
    private RecommendationPriority priority;
    private String reasoning;
    private String suggestedSQL;
    private LocalDateTime detectedAt;
    private Boolean applied;
    private IndexRecommendationMetrics metrics;

    public enum RecommendationPriority {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexRecommendationMetrics {
        private Long estimatedRowsAffected;
        private Double estimatedSpeedupFactor;
        private Long estimatedDiskSpaceBytes;
        private String impactDescription;
        private Long sequentialScans;
        private Long rowsScanned;
        private Long tableSizeBytes;
        private Double averageScanTime;
        private Integer estimatedImprovementPercent;
    }
}
