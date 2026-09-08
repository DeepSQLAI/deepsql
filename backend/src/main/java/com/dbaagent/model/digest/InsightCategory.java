package com.dbaagent.model.digest;

import com.dbaagent.model.PersonaTag;

import java.util.EnumSet;
import java.util.Set;

/**
 * Categories for digest insights.
 *
 * <p>Each category maps to a domain of database health/performance that Brain
 * monitors. Persona tags determine which categories are prioritized in a user's
 * digest.
 */
public enum InsightCategory {

    /**
     * Query performance issues: slow queries, plan regressions, lock contention.
     * High relevance for DBA and APP_ENG personas.
     */
    QUERY_PERFORMANCE("Query Performance", 30),

    /**
     * Index recommendations: missing indexes, unused indexes, ROI candidates.
     * High relevance for DBA personas.
     */
    INDEX_RECOMMENDATIONS("Index Recommendations", 25),

    /**
     * Schema changes: new tables/columns, dropped objects, breaking changes.
     * High relevance for APP_ENG and DATA_ENG personas.
     */
    SCHEMA_CHANGES("Schema Changes", 20),

    /**
     * Table growth anomalies: unexpected size increases, row spikes.
     * Relevant for DBA and DATA_ENG personas.
     */
    GROWTH_ANOMALIES("Growth Anomalies", 20),

    /**
     * Brain learning progress: workload changes, config drift, new patterns.
     * Relevant for DBA personas who care about tuning.
     */
    BRAIN_INTELLIGENCE("Brain Intelligence", 15),

    /**
     * Configuration and tuning: experiment results, knob recommendations.
     * High relevance for DBA personas.
     */
    CONFIG_TUNING("Config & Tuning", 20),

    /**
     * Documentation gaps: tables/columns lacking descriptions.
     * High relevance for DATA_ENG personas.
     */
    DOCUMENTATION_GAPS("Documentation Gaps", 10),

    /**
     * Cost and capacity: spend trends, capacity forecasts.
     * High relevance for EXEC personas.
     */
    COST_CAPACITY("Cost & Capacity", 15),

    /**
     * General alerts from playbooks or system monitoring.
     */
    SYSTEM_ALERTS("System Alerts", 25);

    private final String displayName;
    private final int baseWeight;

    InsightCategory(String displayName, int baseWeight) {
        this.displayName = displayName;
        this.baseWeight = baseWeight;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Base weight for this category (0-100).
     * Higher weight means the category is generally more important.
     */
    public int getBaseWeight() {
        return baseWeight;
    }

    /**
     * Get the weight multiplier for this category given a persona tag.
     *
     * <p>Returns a multiplier (0.5 to 2.0) that adjusts the category's
     * importance based on what the persona cares about.
     */
    public double getPersonaMultiplier(PersonaTag persona) {
        if (persona == null) {
            return 1.0;
        }

        return switch (persona) {
            case DBA -> switch (this) {
                case QUERY_PERFORMANCE -> 2.0;
                case INDEX_RECOMMENDATIONS -> 2.0;
                case CONFIG_TUNING -> 1.8;
                case BRAIN_INTELLIGENCE -> 1.5;
                case GROWTH_ANOMALIES -> 1.3;
                case SYSTEM_ALERTS -> 1.2;
                case SCHEMA_CHANGES -> 0.8;
                case DOCUMENTATION_GAPS -> 0.5;
                case COST_CAPACITY -> 0.7;
            };
            case APP_ENG -> switch (this) {
                case QUERY_PERFORMANCE -> 1.8;
                case SCHEMA_CHANGES -> 2.0;
                case INDEX_RECOMMENDATIONS -> 1.2;
                case SYSTEM_ALERTS -> 1.3;
                case GROWTH_ANOMALIES -> 0.8;
                case BRAIN_INTELLIGENCE -> 0.6;
                case CONFIG_TUNING -> 0.5;
                case DOCUMENTATION_GAPS -> 1.0;
                case COST_CAPACITY -> 0.5;
            };
            case DATA_ENG -> switch (this) {
                case SCHEMA_CHANGES -> 1.8;
                case DOCUMENTATION_GAPS -> 2.0;
                case QUERY_PERFORMANCE -> 1.3;
                case GROWTH_ANOMALIES -> 1.5;
                case INDEX_RECOMMENDATIONS -> 1.0;
                case BRAIN_INTELLIGENCE -> 0.8;
                case CONFIG_TUNING -> 0.5;
                case SYSTEM_ALERTS -> 1.0;
                case COST_CAPACITY -> 0.8;
            };
            case EXEC -> switch (this) {
                case COST_CAPACITY -> 2.0;
                case SYSTEM_ALERTS -> 1.5;
                case QUERY_PERFORMANCE -> 1.2;
                case GROWTH_ANOMALIES -> 1.3;
                case INDEX_RECOMMENDATIONS -> 0.5;
                case SCHEMA_CHANGES -> 0.5;
                case BRAIN_INTELLIGENCE -> 0.5;
                case CONFIG_TUNING -> 0.5;
                case DOCUMENTATION_GAPS -> 0.3;
            };
        };
    }

    /**
     * Get categories most relevant to a persona (multiplier >= 1.5).
     */
    public static Set<InsightCategory> getPrimaryCategories(PersonaTag persona) {
        if (persona == null) {
            return EnumSet.allOf(InsightCategory.class);
        }

        EnumSet<InsightCategory> primary = EnumSet.noneOf(InsightCategory.class);
        for (InsightCategory cat : values()) {
            if (cat.getPersonaMultiplier(persona) >= 1.5) {
                primary.add(cat);
            }
        }
        return primary;
    }
}
