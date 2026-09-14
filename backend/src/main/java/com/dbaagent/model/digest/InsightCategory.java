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
 *
 * <h3>Concrete Persona → Signal Mapping</h3>
 * <ul>
 *   <li><b>DBA:</b> idle-in-txn, locks, autovacuum, bloat, join_collapse_limit cliffs,
 *       plan regressions, config drift</li>
 *   <li><b>APP_ENG:</b> query/ORM patterns, ACCESS EXCLUSIVE risk (DDL blocking),
 *       schema changes affecting their tables, slow queries in their code paths</li>
 *   <li><b>DATA_ENG:</b> ETL load patterns, schema/comment gaps, semantic drift,
 *       growth anomalies, pipeline-affecting changes</li>
 *   <li><b>EXEC:</b> cost/capacity trends, top regression, risk summary (3 bullets max)</li>
 * </ul>
 */
public enum InsightCategory {

    /**
     * Query performance issues: slow queries, plan regressions, ORM patterns.
     * DBA sees plan-level details; APP_ENG sees query patterns from their code.
     */
    QUERY_PERFORMANCE("Query Performance", 30),

    /**
     * Lock and concurrency: idle-in-txn, lock waits, ACCESS EXCLUSIVE blocks.
     * Critical for DBA (operational); APP_ENG cares about DDL blocking risk.
     */
    LOCK_CONCURRENCY("Lock & Concurrency", 28),

    /**
     * Index recommendations: missing indexes, unused indexes, ROI candidates.
     * High relevance for DBA personas.
     */
    INDEX_RECOMMENDATIONS("Index Recommendations", 25),

    /**
     * Schema changes: new tables/columns, dropped objects, breaking changes.
     * APP_ENG: migration/DDL risk. DATA_ENG: semantic drift.
     */
    SCHEMA_CHANGES("Schema Changes", 20),

    /**
     * Table growth anomalies: unexpected size increases, row spikes, bloat.
     * DBA: capacity/vacuum. DATA_ENG: ETL load patterns.
     */
    GROWTH_ANOMALIES("Growth Anomalies", 20),

    /**
     * Brain learning progress: workload changes, config drift, new patterns.
     * DBA: tuning opportunities. DATA_ENG: workload characteristic shifts.
     */
    BRAIN_INTELLIGENCE("Brain Intelligence", 15),

    /**
     * Configuration and tuning: experiment results, knob recommendations,
     * join_collapse_limit cliffs. Primary for DBA personas.
     */
    CONFIG_TUNING("Config & Tuning", 22),

    /**
     * Documentation gaps: tables/columns lacking descriptions, semantic drift.
     * High relevance for DATA_ENG personas building BI/analytics.
     */
    DOCUMENTATION_GAPS("Documentation Gaps", 12),

    /**
     * Cost and capacity: spend trends, capacity forecasts, storage waste.
     * High relevance for EXEC personas (short summaries).
     */
    COST_CAPACITY("Cost & Capacity", 18),

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
     *
     * <h4>Concrete Mapping Rationale</h4>
     * <ul>
     *   <li><b>DBA:</b> Lock/concurrency, query performance, config tuning are operational
     *       priorities. Idle-in-txn, vacuum, bloat, join_collapse_limit cliffs live here.</li>
     *   <li><b>APP_ENG:</b> Schema changes (DDL/migration risk), query patterns from their
     *       code, ACCESS EXCLUSIVE blocking risk matter most.</li>
     *   <li><b>DATA_ENG:</b> Documentation gaps (semantic drift), schema changes (ETL breaks),
     *       growth anomalies (load patterns) are primary.</li>
     *   <li><b>EXEC:</b> Cost/capacity for budget, system alerts for risk, growth for
     *       capacity planning. Short, actionable.</li>
     * </ul>
     */
    public double getPersonaMultiplier(PersonaTag persona) {
        if (persona == null) {
            return 1.0;
        }

        return switch (persona) {
            // DBA: idle-in-txn, locks, autovacuum, bloat, join_collapse_limit, plan regressions
            case DBA -> switch (this) {
                case LOCK_CONCURRENCY -> 2.0;      // idle-in-txn, lock waits, blocking
                case QUERY_PERFORMANCE -> 2.0;     // plan regressions, slow queries
                case INDEX_RECOMMENDATIONS -> 1.8; // index ROI
                case CONFIG_TUNING -> 1.8;         // join_collapse_limit, autovacuum tuning
                case GROWTH_ANOMALIES -> 1.5;      // bloat, vacuum need
                case BRAIN_INTELLIGENCE -> 1.3;    // workload shifts
                case SYSTEM_ALERTS -> 1.2;
                case SCHEMA_CHANGES -> 0.7;        // less operational
                case DOCUMENTATION_GAPS -> 0.4;
                case COST_CAPACITY -> 0.6;
            };
            // APP_ENG: query/ORM patterns, DDL blocking risk, schema changes affecting code
            case APP_ENG -> switch (this) {
                case SCHEMA_CHANGES -> 2.0;        // migrations, DDL affecting their tables
                case QUERY_PERFORMANCE -> 1.8;     // slow queries from their code
                case LOCK_CONCURRENCY -> 1.6;      // ACCESS EXCLUSIVE risk during deploys
                case INDEX_RECOMMENDATIONS -> 1.2;
                case SYSTEM_ALERTS -> 1.2;
                case DOCUMENTATION_GAPS -> 1.0;
                case GROWTH_ANOMALIES -> 0.7;
                case BRAIN_INTELLIGENCE -> 0.5;
                case CONFIG_TUNING -> 0.4;
                case COST_CAPACITY -> 0.4;
            };
            // DATA_ENG: ETL patterns, schema/comment gaps, semantic drift, pipeline health
            case DATA_ENG -> switch (this) {
                case DOCUMENTATION_GAPS -> 2.0;    // semantic drift, BI quality
                case SCHEMA_CHANGES -> 1.8;        // ETL/pipeline breaks
                case GROWTH_ANOMALIES -> 1.6;      // load patterns, capacity
                case QUERY_PERFORMANCE -> 1.3;     // BI query failures
                case BRAIN_INTELLIGENCE -> 1.0;    // workload shifts
                case INDEX_RECOMMENDATIONS -> 0.9;
                case SYSTEM_ALERTS -> 0.9;
                case LOCK_CONCURRENCY -> 0.6;
                case CONFIG_TUNING -> 0.5;
                case COST_CAPACITY -> 0.7;
            };
            // EXEC: cost/capacity/risk summaries (short, 3 bullets max)
            case EXEC -> switch (this) {
                case COST_CAPACITY -> 2.0;         // budget, spend trends
                case SYSTEM_ALERTS -> 1.6;         // risk awareness
                case GROWTH_ANOMALIES -> 1.4;      // capacity planning
                case QUERY_PERFORMANCE -> 1.1;     // top regression headline
                case LOCK_CONCURRENCY -> 0.8;      // only if critical
                case INDEX_RECOMMENDATIONS -> 0.4;
                case SCHEMA_CHANGES -> 0.4;
                case BRAIN_INTELLIGENCE -> 0.4;
                case CONFIG_TUNING -> 0.4;
                case DOCUMENTATION_GAPS -> 0.2;
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
