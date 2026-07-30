package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Entity for storing individual table role classification.
 * Implements BRAIN-design.md Section 6: "Sub-Graph Classification"
 *
 * Table Roles:
 * - FACT: Central transaction/event table with many rows
 * - DIMENSION: Descriptive attributes table with fewer rows
 * - BRIDGE: Resolves many-to-many relationships
 * - LOOKUP: Small reference table (countries, statuses, etc.)
 * - ORPHANED: No relationships to other tables
 * - AGGREGATE: Pre-computed summary table
 *
 * Access Patterns:
 * - READ_HEAVY: SELECT >> INSERT/UPDATE
 * - WRITE_HEAVY: INSERT/UPDATE >> SELECT
 * - APPEND_ONLY: High inserts, near-zero updates/deletes
 * - UPDATE_INTENSIVE: High update rate, low insert rate
 * - MIXED_WORKLOAD: Balanced read/write ratio
 * - RARELY_ACCESSED: Low activity overall
 *
 * Temporal Types:
 * - TIME_SERIES: Has timestamp + monotonically increasing data
 * - SCD_TYPE_1: Has updated_at but no history
 * - SCD_TYPE_2: Has effective_from, effective_to, is_current
 * - AUDIT_LOG: Audit/log table with action tracking
 * - EVENT_SOURCING: Immutable event records
 * - SNAPSHOT: Point-in-time snapshot data
 * - NONE: No temporal pattern detected
 *
 * Business Domains:
 * - CUSTOMER: Users, customers, accounts, profiles
 * - PRODUCT: Products, items, inventory, catalog
 * - TRANSACTION: Orders, payments, invoices, bookings
 * - LOCATION: Addresses, regions, countries, stores
 * - TEMPORAL: Dates, times, periods, calendars
 * - CONFIGURATION: Settings, config, parameters, flags
 * - SECURITY: Roles, permissions, access_control
 * - COMMUNICATION: Emails, notifications, messages
 * - UNKNOWN: Cannot determine domain
 *
 * Sensitivity Levels:
 * - PII_HIGH: SSN, passport, government ID
 * - PII_MEDIUM: Email, phone, name, address
 * - FINANCIAL: Credit card, bank account, salary
 * - HEALTH: Medical records (HIPAA)
 * - REGULATED: Subject to GDPR, CCPA, SOX
 * - PUBLIC: No sensitive data
 *
 * Partition Readiness:
 * - PARTITION_CANDIDATE_TIME: Large table suitable for time-based partitioning
 * - PARTITION_CANDIDATE_RANGE: Large table suitable for range partitioning
 * - PARTITION_CANDIDATE_LIST: Large table suitable for list partitioning
 * - ALREADY_PARTITIONED: Native partitioning detected
 * - NOT_PARTITION_CANDIDATE: Small table or no clear partition key
 */
@Entity
@Table(name = "table_classification", indexes = {
    @Index(name = "idx_table_class_schema", columnList = "schemaClassificationId"),
    @Index(name = "idx_table_class_connection", columnList = "connectionId"),
    @Index(name = "idx_table_class_role", columnList = "connectionId,tableRole")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_table_class", columnNames = {"schemaClassificationId", "tableName"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableClassification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 255)
    private String schemaClassificationId;

    @Column(nullable = false, length = 255)
    private String connectionId;

    @Column(nullable = false, length = 255)
    private String tableName;

    /**
     * Table role in schema.
     */
    @Column(nullable = false, length = 50)
    private String tableRole;  // FACT, DIMENSION, BRIDGE, LOOKUP, ORPHANED, AGGREGATE

    /**
     * Confidence in classification (0-100).
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    // ==================== Basic Metrics ====================

    @Column
    private Long rowCount;

    @Column
    private Integer columnCount;

    @Column
    private Integer foreignKeyCount;

    @Column
    private Integer inboundJoinCount;  // How many tables join TO this

    @Column
    private Integer outboundJoinCount;  // How many tables this joins TO

    // ==================== Basic Characteristics ====================

    @Column
    private Boolean isNormalized;

    @Column
    private Boolean hasSurrogateKey;

    @Column(length = 50)
    private String granularityLevel;  // DETAIL, SUMMARY, AGGREGATE

    // ==================== Graph Position ====================

    @Column
    private Integer depthFromFact;  // 0 = fact, 1 = direct dimension, 2+ = snowflaked

    @Column
    private Integer clusterId;  // Which star cluster

    @Column(columnDefinition = "TEXT")
    private String classificationReasoning;

    // ==================== Phase 1: Access Pattern Classification ====================

    @Column(length = 50)
    private String accessPattern;  // READ_HEAVY, WRITE_HEAVY, APPEND_ONLY, UPDATE_INTENSIVE, MIXED_WORKLOAD, RARELY_ACCESSED

    @Column
    @Builder.Default
    private Long readCount = 0L;

    @Column
    @Builder.Default
    private Long writeCount = 0L;

    @Column
    @Builder.Default
    private Long updateCount = 0L;

    @Column
    @Builder.Default
    private Long deleteCount = 0L;

    @Column(precision = 10, scale = 4)
    private BigDecimal readWriteRatio;

    // ==================== Phase 1: Anti-Pattern Detection ====================

    /**
     * JSON array of detected anti-patterns.
     * Each element: { "type": "GOD_TABLE", "severity": "HIGH", "details": {...}, "recommendation": "..." }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> antiPatterns;

    @Column
    @Builder.Default
    private Integer antiPatternCount = 0;

    @Column(length = 20)
    private String antiPatternSeverity;  // CRITICAL, HIGH, MEDIUM, LOW, NONE

    // ==================== Phase 2: Temporal Classification ====================

    @Column(length = 50)
    private String temporalType;  // TIME_SERIES, SCD_TYPE_1, SCD_TYPE_2, AUDIT_LOG, EVENT_SOURCING, SNAPSHOT, NONE

    @Column
    @Builder.Default
    private Boolean hasTimestampColumns = false;

    /**
     * JSON array of timestamp column details.
     * Each element: { "column": "created_at", "type": "CREATION", "indexed": true }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> timestampColumns;

    // ==================== Phase 2: Table Health Score ====================

    @Column(precision = 5, scale = 2)
    private BigDecimal healthScore;

    /**
     * JSON breakdown of health score components.
     * { "index_efficiency": 85, "data_quality": 90, "schema_design": 75, "access_efficiency": 80, "maintenance_health": 70 }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> healthBreakdown;

    // ==================== Phase 3: Business Domain Classification ====================

    @Column(length = 50)
    private String businessDomain;  // CUSTOMER, PRODUCT, TRANSACTION, LOCATION, TEMPORAL, CONFIGURATION, SECURITY, COMMUNICATION, UNKNOWN

    @Column(precision = 5, scale = 2)
    private BigDecimal domainConfidence;

    /**
     * JSON array of indicators that led to domain classification.
     * Each element: { "type": "COLUMN_PATTERN", "column": "email", "matches": "customer" }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> domainIndicators;

    // ==================== Phase 4: Data Sensitivity Classification ====================

    @Column(length = 50)
    private String sensitivityLevel;  // PII_HIGH, PII_MEDIUM, FINANCIAL, HEALTH, REGULATED, PUBLIC

    /**
     * JSON array of sensitive columns detected.
     * Each element: { "column": "ssn", "type": "PII_HIGH", "confidence": 95.0 }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> sensitiveColumns;

    @Column(precision = 5, scale = 2)
    private BigDecimal sensitivityConfidence;

    // ==================== Phase 4: Partitioning Readiness ====================

    @Column(length = 50)
    private String partitionReadiness;  // PARTITION_CANDIDATE_TIME, PARTITION_CANDIDATE_RANGE, PARTITION_CANDIDATE_LIST, ALREADY_PARTITIONED, NOT_PARTITION_CANDIDATE

    /**
     * JSON array of potential partition key columns.
     * Each element: { "column": "created_at", "type": "TIME", "cardinality": 365, "benefit_estimate": 0.85 }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> partitionKeyCandidates;

    @Column(precision = 5, scale = 2)
    private BigDecimal estimatedPartitionBenefit;

    // ==================== Phase 5: Relationship Summary ====================

    /**
     * JSON summary of table relationships.
     * { "strong_count": 5, "weak_count": 2, "inferred_count": 3, "missing_fks": ["user_id"], "integrity_issues": [...] }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> relationshipSummary;

    // ==================== Phase 6: Data Lifecycle Classification ====================

    @Column(length = 20)
    private String dataLifecycle;  // HOT, WARM, COLD, FROZEN

    @Column
    private Integer daysSinceLastAccess;

    @Column(precision = 10, scale = 2)
    private BigDecimal accessFrequencyPerDay;

    // ==================== Phase 6: Cache Affinity Classification ====================

    @Column(length = 30)
    private String cacheAffinity;  // HIGH_CACHE_VALUE, MEDIUM_CACHE_VALUE, LOW_CACHE_VALUE, NO_CACHE_BENEFIT

    @Column(precision = 5, scale = 2)
    private BigDecimal cacheAffinityScore;

    @Column(precision = 5, scale = 2)
    private BigDecimal estimatedCacheHitRate;

    // ==================== Phase 6: Query Complexity Classification ====================

    @Column(length = 30)
    private String queryComplexity;  // SIMPLE_LOOKUP, RANGE_SCAN, COMPLEX_JOIN, AGGREGATION_HEAVY, FULL_SCAN, MIXED

    @Column(precision = 5, scale = 2)
    private BigDecimal avgQueryComplexityScore;

    @Column
    private Integer analyzedQueryCount;

    // ==================== Phase 6: Schema Evolution Risk ====================

    @Column(length = 20)
    private String schemaEvolutionRisk;  // CRITICAL, HIGH, MEDIUM, LOW, MINIMAL

    @Column(precision = 5, scale = 2)
    private BigDecimal schemaRiskScore;

    @Column(length = 100)
    private String estimatedDdlImpact;

    /**
     * JSON object with risk factors breakdown.
     * { "size_factor": 70.5, "dependency_factor": 45.0, "index_factor": 30.0, "column_factor": 25.0 }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> schemaRiskFactors;

    // ==================== Phase 6: Denormalization Candidate ====================

    @Column
    @Builder.Default
    private Boolean denormalizationCandidate = false;

    @Column(precision = 5, scale = 2)
    private BigDecimal denormalizationBenefitScore;

    /**
     * JSON array of denormalization recommendations.
     * Each element: { "target_table": "users", "join_frequency": 150, "strategy": "Add redundant columns" }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> denormalizationCandidates;

    // ==================== Phase 6: Cost Attribution ====================

    @Column(length = 20)
    private String costTier;  // HIGH_COST, MEDIUM_COST, LOW_COST, MINIMAL_COST

    @Column(precision = 10, scale = 4)
    private BigDecimal estimatedMonthlyCost;

    @Column(precision = 5, scale = 2)
    private BigDecimal costPercentageOfDb;

    /**
     * JSON breakdown of cost components.
     * { "storage_cost": 10.50, "iops_cost": 2.30, "compute_cost": 5.20 }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> costBreakdown;

    // ==================== Phase 6: Sharding Readiness ====================

    @Column(length = 30)
    private String shardingReadiness;  // READY, NEEDS_REFACTORING, COMPLEX, NOT_RECOMMENDED, NOT_NEEDED

    @Column(precision = 5, scale = 2)
    private BigDecimal shardingReadinessScore;

    @Column
    private Integer recommendedShardCount;

    /**
     * JSON array of shard key candidates.
     * Each element: { "column": "tenant_id", "suitability_score": 90, "pattern_match": "tenant" }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> shardKeyCandidates;

    // ==================== Phase 6: Data Quality Score ====================

    @Column(length = 20)
    private String dataQualityLevel;  // EXCELLENT, GOOD, FAIR, POOR

    @Column(precision = 5, scale = 2)
    private BigDecimal dataQualityScore;

    /**
     * JSON breakdown of quality dimensions.
     * { "completeness": 85.0, "validity": 90.0, "uniqueness": 95.0, "consistency": 80.0, "timeliness": 70.0 }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> qualityDimensions;

    // ==================== Phase 6: Dependency Criticality ====================

    @Column(length = 20)
    private String dependencyCriticality;  // CRITICAL, HIGH, MEDIUM, LOW, ISOLATED

    @Column(precision = 5, scale = 2)
    private BigDecimal dependencyCriticalityScore;

    @Column
    private Integer dependentTableCount;

    @Column
    private Integer dependencyTableCount;

    @Column
    private Integer cascadeImpactCount;

    // ==================== Phase 6: Growth Prediction ====================

    @Column(length = 20)
    private String growthCategory;  // EXPLOSIVE, RAPID, STEADY, SLOW, STABLE, SHRINKING

    @Column(precision = 10, scale = 2)
    private BigDecimal monthlyGrowthRate;

    @Column
    private Long predictedSize90Days;

    @Column
    private Long predictedRows90Days;

    @Column
    private Integer daysToSizeWarning;

    @Column(precision = 5, scale = 2)
    private BigDecimal growthPredictionConfidence;

    // ==================== Timestamps ====================

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
