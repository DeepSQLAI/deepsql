package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseRecommendation {

    private String id;
    private String connectionId;
    private RecommendationType type;
    private String title;
    private String description;
    private String reasoning;
    private String suggestedAction;
    private String suggestedSQL;
    private RecommendationPriority priority;
    private String impact;
    private String riskLevel;
    private LocalDateTime detectedAt;
    private Boolean applied;
    private LocalDateTime appliedAt;
    private String appliedBy;

    public enum RecommendationType {
        INDEX,              // Missing index
        QUERY_OPTIMIZATION, // Slow query optimization
        VACUUM,            // PostgreSQL vacuum needed
        TABLE_BLOAT,       // Table bloat issue
        CONFIGURATION,     // Database configuration
        STATISTICS,        // Statistics update needed
        PARTITIONING,      // Table partitioning suggestion
        ARCHIVAL,          // Data archival opportunity
        NORMALIZATION,     // Schema normalization
        DENORMALIZATION    // Denormalization for performance
    }

    public enum RecommendationPriority {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }
}
