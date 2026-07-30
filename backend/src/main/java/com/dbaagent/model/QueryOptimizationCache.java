package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Caches AI-powered query optimization results to avoid redundant API calls.
 * Results are stored per connection and query fingerprint.
 */
@Entity
@Table(name = "query_optimization_cache",
    uniqueConstraints = @UniqueConstraint(columnNames = {"connection_id", "query_fingerprint"}),
    indexes = {
        @Index(name = "idx_qoc_connection_fingerprint", columnList = "connection_id, query_fingerprint"),
        @Index(name = "idx_qoc_created_at", columnList = "created_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryOptimizationCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    /**
     * Query fingerprint/hash that uniquely identifies the query pattern.
     * This is the normalized query hash (queryId from SlowQuery).
     */
    @Column(name = "query_fingerprint", nullable = false)
    private String queryFingerprint;

    /**
     * The original query text that was optimized.
     */
    @Column(name = "original_query", columnDefinition = "TEXT")
    private String originalQuery;

    /**
     * The AI-suggested optimized query.
     */
    @Column(name = "optimized_query", columnDefinition = "TEXT")
    private String optimizedQuery;

    /**
     * JSON-serialized list of optimization suggestions.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggestions", columnDefinition = "jsonb")
    private String suggestionsJson;

    /**
     * JSON-serialized list of index recommendations.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "index_recommendations", columnDefinition = "jsonb")
    private String indexRecommendationsJson;

    /**
     * AI explanation of the optimization strategy.
     */
    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    /**
     * Estimated performance improvement percentage (0-100).
     */
    @Column(name = "estimated_improvement")
    private Double estimatedImprovement;

    /**
     * JSON-serialized EXPLAIN plan analysis (if available).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explain_analysis", columnDefinition = "jsonb")
    private String explainAnalysisJson;

    /**
     * When this optimization was first generated.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * When this optimization was last refreshed.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Number of times this cached result has been accessed.
     */
    @Column(name = "access_count")
    @Builder.Default
    private Integer accessCount = 0;

    /**
     * Last time this cached result was accessed.
     */
    @Column(name = "last_accessed_at")
    private LocalDateTime lastAccessedAt;

    /**
     * Version of the rewrite engine that produced this entry. Cached results
     * from an older engine version are ignored (treated as a cache miss) so
     * optimizer improvements reach previously-optimized queries instead of
     * serving a stale rewrite forever. Nullable — null means "pre-versioning"
     * and is always treated as stale.
     */
    @Column(name = "engine_version")
    private Integer engineVersion;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (accessCount == null) {
            accessCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Record an access to this cached result.
     */
    public void recordAccess() {
        this.accessCount = (this.accessCount == null ? 0 : this.accessCount) + 1;
        this.lastAccessedAt = LocalDateTime.now();
    }
}
