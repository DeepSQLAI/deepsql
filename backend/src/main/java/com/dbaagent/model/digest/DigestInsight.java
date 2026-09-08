package com.dbaagent.model.digest;

import lombok.Builder;
import lombok.Data;
import lombok.With;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A single ranked insight for inclusion in a digest.
 *
 * <p>Insights are mined from Brain stores, slow-query analysis, schema changes,
 * growth anomalies, and other sources. Each insight carries enough context
 * for rendering in Slack/email and for suppression logic.
 *
 * <h3>Ranking Formula</h3>
 * <pre>
 * finalScore = baseScore
 *            × severityMultiplier
 *            × freshnessMultiplier
 *            × roleRelevanceMultiplier
 *            × actionabilityBonus
 * </pre>
 *
 * <h3>Suppression</h3>
 * <p>The {@code signatureKey} is used to detect duplicates across digests.
 * If the same signature was included in a recent digest and the insight
 * has not materially changed, it is suppressed to avoid "blast of the same
 * top item" syndrome.
 */
@Data
@Builder
@With
public class DigestInsight {

    /**
     * Category of this insight.
     */
    private InsightCategory category;

    /**
     * Short headline for the insight (one line).
     * Example: "3 high-impact index recommendations pending"
     */
    private String headline;

    /**
     * Detailed description with context.
     * Example: "Index on orders(customer_id) could save 2.3s/query..."
     */
    private String description;

    /**
     * Severity level (0-100). Higher = more urgent.
     * Maps to CRITICAL=100, HIGH=75, WARNING=50, INFO=25.
     */
    private int severity;

    /**
     * When this insight was detected or last updated.
     */
    private LocalDateTime timestamp;

    /**
     * Whether this insight is actionable (user can do something about it).
     * Actionable insights get a ranking bonus.
     */
    @Builder.Default
    private boolean actionable = true;

    /**
     * Suggested action for the user, if any.
     * Example: "Run ANALYZE on affected tables"
     */
    private String suggestedAction;

    /**
     * Source entity ID (e.g., alert ID, recommendation ID).
     * Used for deduplication and linking back to the source.
     */
    private String sourceId;

    /**
     * Source entity type (e.g., "BrainV2Alert", "IndexRecommendation").
     */
    private String sourceType;

    /**
     * Unique signature for deduplication across digests.
     * Format: "{category}:{sourceType}:{key-fields-hash}"
     * Example: "INDEX_RECOMMENDATIONS:IndexRecommendation:orders_customer_id_idx"
     */
    private String signatureKey;

    /**
     * Connection ID this insight belongs to.
     */
    private String connectionId;

    /**
     * Additional context data for rendering.
     * Keys depend on the insight type.
     */
    private Map<String, Object> contextData;

    /**
     * The final computed rank score (higher = more important).
     * Set by the assembler after applying all multipliers.
     */
    private double rankScore;

    /**
     * Whether this insight was acknowledged by the user.
     * Acknowledged insights are typically suppressed from digests.
     */
    @Builder.Default
    private boolean acknowledged = false;

    /**
     * Whether this insight appeared in the last digest to this recipient.
     * Used for "since last digest" filtering.
     */
    @Builder.Default
    private boolean appearedInLastDigest = false;

    /**
     * Tables involved in this insight (for schema-based filtering).
     */
    private String[] involvedTables;

    /**
     * Get severity as a display string.
     */
    public String getSeverityLabel() {
        if (severity >= 90) return "CRITICAL";
        if (severity >= 70) return "HIGH";
        if (severity >= 40) return "WARNING";
        return "INFO";
    }

    /**
     * Get the severity multiplier for ranking (1.0 to 2.0).
     */
    public double getSeverityMultiplier() {
        return 1.0 + (severity / 100.0);
    }

    /**
     * Get the freshness multiplier for ranking (0.5 to 1.5).
     * Insights from the last hour are boosted; older insights decay.
     */
    public double getFreshnessMultiplier() {
        if (timestamp == null) {
            return 1.0;
        }
        LocalDateTime now = LocalDateTime.now();
        long hoursAgo = java.time.Duration.between(timestamp, now).toHours();

        if (hoursAgo <= 1) return 1.5;
        if (hoursAgo <= 6) return 1.3;
        if (hoursAgo <= 24) return 1.1;
        if (hoursAgo <= 72) return 1.0;
        if (hoursAgo <= 168) return 0.8;
        return 0.5;
    }

    /**
     * Get the actionability bonus (1.0 or 1.2).
     */
    public double getActionabilityBonus() {
        return actionable && suggestedAction != null ? 1.2 : 1.0;
    }

    /**
     * Convenience builder method for creating from an alert.
     */
    public static DigestInsightBuilder fromBrainAlert() {
        return DigestInsight.builder()
            .sourceType("BrainV2Alert");
    }

    /**
     * Convenience builder method for creating from a recommendation.
     */
    public static DigestInsightBuilder fromIndexRecommendation() {
        return DigestInsight.builder()
            .sourceType("IndexRecommendation")
            .category(InsightCategory.INDEX_RECOMMENDATIONS);
    }

    /**
     * Convenience builder method for creating from a schema change.
     */
    public static DigestInsightBuilder fromSchemaChange() {
        return DigestInsight.builder()
            .sourceType("SchemaChange")
            .category(InsightCategory.SCHEMA_CHANGES);
    }

    /**
     * Convenience builder method for creating from a growth anomaly.
     */
    public static DigestInsightBuilder fromGrowthAnomaly() {
        return DigestInsight.builder()
            .sourceType("GrowthAnomaly")
            .category(InsightCategory.GROWTH_ANOMALIES);
    }
}
