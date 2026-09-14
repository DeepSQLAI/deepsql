package com.dbaagent.model.digest;

import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.Role;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Result of assembling a personalized digest for a user.
 *
 * <p>Contains the ranked insights tailored to the user's role and persona,
 * along with metadata about the assembly process.
 */
@Data
@Builder
public class DigestAssemblyResult {

    /**
     * Username this digest was assembled for.
     */
    private String username;

    /**
     * Connection ID this digest covers.
     */
    private String connectionId;

    /**
     * User's RBAC role at assembly time.
     */
    private Role role;

    /**
     * User's persona tag (may be null for role-only personalization).
     */
    private PersonaTag personaTag;

    /**
     * When this digest was assembled.
     */
    private LocalDateTime assembledAt;

    /**
     * Time window start for insights (e.g., since last digest).
     */
    private LocalDateTime windowStart;

    /**
     * Time window end for insights.
     */
    private LocalDateTime windowEnd;

    /**
     * Ranked insights for this user (highest rank first).
     */
    private List<DigestInsight> insights;

    /**
     * Summary counts by category for quick overview.
     */
    private Map<InsightCategory, Integer> categoryCounts;

    /**
     * Total insights considered before filtering/ranking.
     */
    private int totalCandidates;

    /**
     * Insights suppressed as duplicates from last digest.
     */
    private int suppressedDuplicates;

    /**
     * Insights filtered out due to acknowledgment.
     */
    private int filteredAcknowledged;

    /**
     * Whether this is an empty digest (no actionable insights).
     */
    @Builder.Default
    private boolean empty = false;

    /**
     * Executive summary for EXEC personas (3 bullets max).
     * Null for other personas.
     */
    private List<String> executiveSummary;

    /**
     * Top decision ask for EXEC personas.
     * Null for other personas.
     */
    private String decisionAsk;

    /**
     * Get insights by category.
     */
    public List<DigestInsight> getInsightsByCategory(InsightCategory category) {
        if (insights == null) return List.of();
        return insights.stream()
            .filter(i -> i.getCategory() == category)
            .toList();
    }

    /**
     * Get top N insights.
     */
    public List<DigestInsight> getTopInsights(int n) {
        if (insights == null) return List.of();
        return insights.stream().limit(n).toList();
    }

    /**
     * Check if digest has critical insights.
     */
    public boolean hasCriticalInsights() {
        return insights != null && insights.stream()
            .anyMatch(i -> i.getSeverity() >= 90);
    }

    /**
     * Check if digest has high-severity insights.
     */
    public boolean hasHighSeverityInsights() {
        return insights != null && insights.stream()
            .anyMatch(i -> i.getSeverity() >= 70);
    }

    /**
     * Get the headline for the digest.
     */
    public String getHeadline() {
        if (empty || insights == null || insights.isEmpty()) {
            return "No new insights since your last digest";
        }
        int count = insights.size();
        if (hasCriticalInsights()) {
            long criticalCount = insights.stream().filter(i -> i.getSeverity() >= 90).count();
            return String.format("%d insight%s (%d critical)",
                count, count != 1 ? "s" : "", criticalCount);
        }
        if (hasHighSeverityInsights()) {
            long highCount = insights.stream().filter(i -> i.getSeverity() >= 70).count();
            return String.format("%d insight%s (%d high priority)",
                count, count != 1 ? "s" : "", highCount);
        }
        return String.format("%d new insight%s", count, count != 1 ? "s" : "");
    }

    /**
     * Create an empty result for a user with no insights.
     */
    public static DigestAssemblyResult empty(String username, String connectionId,
                                              Role role, PersonaTag personaTag) {
        return DigestAssemblyResult.builder()
            .username(username)
            .connectionId(connectionId)
            .role(role)
            .personaTag(personaTag)
            .assembledAt(LocalDateTime.now())
            .insights(List.of())
            .categoryCounts(Map.of())
            .empty(true)
            .build();
    }
}
