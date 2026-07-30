/**
 * Centralized help text definitions for Brain tab terminology.
 * Provides tooltips and explanations for all jargon used in the Brain features.
 */

// =============================================================================
// TABLE ROLES
// =============================================================================
export const TABLE_ROLES = {
  FACT: {
    title: 'Fact Table',
    description: 'Central table containing measurable, quantitative data (metrics, transactions, events). Forms the core of a star/snowflake schema.',
    examples: 'orders, transactions, page_views, sales',
    icon: 'star'
  },
  DIMENSION: {
    title: 'Dimension Table',
    description: 'Descriptive table containing attributes used to filter, group, or label fact data. Provides context to facts.',
    examples: 'customers, products, dates, locations',
    icon: 'cube'
  },
  BRIDGE: {
    title: 'Bridge Table',
    description: 'Junction table that resolves many-to-many relationships between two tables. Contains foreign keys to both related tables.',
    examples: 'user_roles, product_categories, order_items',
    icon: 'link'
  },
  LOOKUP: {
    title: 'Lookup Table',
    description: 'Small reference table containing static or semi-static data used for code translations or valid value lists.',
    examples: 'countries, status_codes, currencies, payment_types',
    icon: 'book'
  },
  AGGREGATE: {
    title: 'Aggregate Table',
    description: 'Pre-computed summary table containing rolled-up metrics for faster query performance. Trades storage for speed.',
    examples: 'daily_sales_summary, monthly_user_stats',
    icon: 'layers'
  },
  ORPHANED: {
    title: 'Orphaned Table',
    description: 'Table with no detected relationships to other tables. May be unused, poorly designed, or missing foreign keys.',
    examples: 'legacy_data, temp_imports, abandoned_features',
    icon: 'alert-circle'
  },
  STAGING: {
    title: 'Staging Table',
    description: 'Temporary table used for data loading, transformation, or ETL processes. Usually cleared after processing.',
    examples: 'stg_orders, import_buffer, etl_temp',
    icon: 'upload'
  },
  AUDIT: {
    title: 'Audit Table',
    description: 'Table that tracks changes, user actions, or system events for compliance and debugging purposes.',
    examples: 'audit_log, change_history, user_activity',
    icon: 'clipboard'
  },
  CONFIGURATION: {
    title: 'Configuration Table',
    description: 'Table storing application settings, feature flags, or system parameters.',
    examples: 'app_settings, feature_flags, system_config',
    icon: 'settings'
  }
};

// =============================================================================
// SCHEMA PATTERNS
// =============================================================================
export const SCHEMA_PATTERNS = {
  STAR: {
    title: 'Star Schema',
    description: 'Simple dimensional model with a central fact table directly connected to dimension tables. Optimized for query performance.',
    pros: 'Simple queries, fast aggregations, easy to understand',
    cons: 'Denormalized dimensions, potential data redundancy'
  },
  SNOWFLAKE: {
    title: 'Snowflake Schema',
    description: 'Normalized dimensional model where dimension tables are further normalized into sub-dimensions.',
    pros: 'Less data redundancy, smaller storage, maintains referential integrity',
    cons: 'More complex queries, more joins required'
  },
  HYBRID: {
    title: 'Hybrid Schema',
    description: 'Combination of star and snowflake patterns, balancing query performance with normalization where needed.',
    pros: 'Flexible design, optimized for specific use cases',
    cons: 'More complex to maintain and understand'
  }
};

// =============================================================================
// ANTI-PATTERNS
// =============================================================================
export const ANTI_PATTERNS = {
  WIDE_TABLE: {
    title: 'Wide Table',
    description: 'Table with excessive columns (typically >30). Indicates poor normalization and can cause performance issues.',
    impact: 'Slower queries, wasted storage, difficult maintenance',
    recommendation: 'Consider splitting into related tables or using vertical partitioning'
  },
  GOD_TABLE: {
    title: 'God Table',
    description: 'Single table trying to store everything - too many columns, relationships, and responsibilities.',
    impact: 'Performance bottleneck, hard to maintain, high contention',
    recommendation: 'Decompose into focused, single-responsibility tables'
  },
  MISSING_PRIMARY_KEY: {
    title: 'Missing Primary Key',
    description: 'Table without a defined primary key constraint, making row identification unreliable.',
    impact: 'Cannot guarantee uniqueness, ORM issues, replication problems',
    recommendation: 'Add a primary key (natural or surrogate)'
  },
  MISSING_FOREIGN_KEY: {
    title: 'Missing Foreign Key',
    description: 'Related tables without foreign key constraints, allowing orphaned records.',
    impact: 'Data integrity issues, orphaned records, inconsistent data',
    recommendation: 'Add foreign key constraints to enforce relationships'
  },
  MISSING_INDEX: {
    title: 'Missing Index',
    description: 'Frequently queried columns without supporting indexes.',
    impact: 'Full table scans, slow queries, high CPU usage',
    recommendation: 'Add indexes on columns used in WHERE, JOIN, and ORDER BY'
  },
  MISSING_TIMESTAMP: {
    title: 'Missing Timestamp',
    description: 'Table without created_at/updated_at columns for tracking record changes.',
    impact: 'Cannot track when data changed, difficult debugging and auditing',
    recommendation: 'Add created_at and updated_at timestamp columns'
  },
  NULLABLE_KEY_COLUMN: {
    title: 'Nullable Key Column',
    description: 'Column used in joins or lookups that allows NULL values.',
    impact: 'Unexpected query results, join issues, data quality problems',
    recommendation: 'Add NOT NULL constraint or handle NULLs explicitly'
  },
  POLYMORPHIC_ASSOCIATION: {
    title: 'Polymorphic Association',
    description: 'Pattern using type/id columns to reference multiple tables, bypassing foreign keys.',
    impact: 'No referential integrity, complex queries, harder to maintain',
    recommendation: 'Use separate foreign key columns or inheritance patterns'
  },
  EAV_PATTERN: {
    title: 'Entity-Attribute-Value Pattern',
    description: 'Schema storing attributes as rows (entity_id, attribute_name, value) instead of columns.',
    impact: 'Complex queries, poor performance, no type safety',
    recommendation: 'Use proper columns or JSON fields for flexible attributes'
  },
  SOFT_DELETE_WITHOUT_INDEX: {
    title: 'Soft Delete Without Index',
    description: 'Using a deleted/is_active flag without indexing it, causing full scans.',
    impact: 'All queries scan deleted records, poor performance',
    recommendation: 'Add partial index on active records or use partitioning'
  },
  RESERVED_WORD_COLUMN: {
    title: 'Reserved Word Column',
    description: 'Column name using SQL reserved words (e.g., order, user, table).',
    impact: 'Requires quoting in queries, potential SQL errors',
    recommendation: 'Rename columns to avoid reserved words'
  },
  INCONSISTENT_NAMING: {
    title: 'Inconsistent Naming',
    description: 'Mixed naming conventions (camelCase, snake_case, UPPERCASE) in the same schema.',
    impact: 'Confusing for developers, error-prone queries',
    recommendation: 'Standardize on one naming convention (snake_case recommended)'
  }
};

// =============================================================================
// ANTI-PATTERN SEVERITY LEVELS
// =============================================================================
export const SEVERITY_LEVELS = {
  CRITICAL: {
    title: 'Critical Severity',
    description: 'Immediate action required. Can cause data loss, corruption, or major performance issues.',
    color: 'var(--color-danger)'
  },
  HIGH: {
    title: 'High Severity',
    description: 'Should be addressed soon. Significant impact on performance or data quality.',
    color: 'var(--color-danger)'
  },
  MEDIUM: {
    title: 'Medium Severity',
    description: 'Plan to address. Noticeable impact but not urgent.',
    color: 'var(--color-warning)'
  },
  LOW: {
    title: 'Low Severity',
    description: 'Nice to fix. Minor impact, best practice improvement.',
    color: 'var(--color-light-6)'
  }
};

// =============================================================================
// ACCESS PATTERNS
// =============================================================================
export const ACCESS_PATTERNS = {
  READ_HEAVY: {
    title: 'Read-Heavy',
    description: 'Table is primarily read (>80% reads). Good candidate for caching and read replicas.',
    optimization: 'Add indexes, consider caching, use read replicas'
  },
  WRITE_HEAVY: {
    title: 'Write-Heavy',
    description: 'Table receives frequent inserts/updates (>50% writes). May need write optimization.',
    optimization: 'Batch writes, consider partitioning, optimize indexes for writes'
  },
  APPEND_ONLY: {
    title: 'Append-Only',
    description: 'Table only receives inserts, no updates or deletes. Ideal for time-series and logging.',
    optimization: 'Use time-based partitioning, consider columnar storage'
  },
  UPDATE_INTENSIVE: {
    title: 'Update-Intensive',
    description: 'Table receives many updates to existing rows. Watch for lock contention.',
    optimization: 'Minimize index count, consider row-level locking, batch updates'
  },
  MIXED_WORKLOAD: {
    title: 'Mixed Workload',
    description: 'Balanced read and write operations. Requires careful index planning.',
    optimization: 'Balance read and write optimization, monitor for contention'
  },
  RARELY_ACCESSED: {
    title: 'Rarely Accessed',
    description: 'Table has minimal activity. May be candidate for archival or deletion.',
    optimization: 'Consider archiving, moving to cold storage, or removal'
  },
  UNKNOWN: {
    title: 'Unknown Pattern',
    description: 'Insufficient data to determine access pattern. Needs more observation.',
    optimization: 'Enable query logging and monitor access over time'
  }
};

// =============================================================================
// DATA LIFECYCLE
// =============================================================================
export const DATA_LIFECYCLE = {
  HOT: {
    title: 'Hot Data',
    description: 'Actively accessed data (last 7 days, >100 operations/day). Keep on fastest storage.',
    recommendation: 'Primary storage tier, aggressive caching, optimize for speed'
  },
  WARM: {
    title: 'Warm Data',
    description: 'Moderately accessed data (last 30 days, 10-100 ops/day). Balance cost and performance.',
    recommendation: 'Standard storage, selective caching, read replica offloading'
  },
  COLD: {
    title: 'Cold Data',
    description: 'Rarely accessed data (30-90 days old, <10 ops/day). Optimize for cost.',
    recommendation: 'Consider compression, cold storage tier, archival policies'
  },
  FROZEN: {
    title: 'Frozen Data',
    description: 'Inactive data (>90 days, minimal access). Strong archival/deletion candidate.',
    recommendation: 'Archive to cold storage, evaluate retention policy, consider deletion'
  }
};

// =============================================================================
// CACHE AFFINITY
// =============================================================================
export const CACHE_AFFINITY = {
  HIGH_CACHE_VALUE: {
    title: 'High Cache Value',
    description: 'Excellent caching candidate: frequently read, rarely updated, manageable size (<1M rows).',
    recommendation: 'Implement Redis/Memcached caching, use cache-aside pattern'
  },
  MEDIUM_CACHE_VALUE: {
    title: 'Medium Cache Value',
    description: 'Moderate caching benefit: decent read ratio, acceptable size, some updates.',
    recommendation: 'Cache hot keys with short TTL, selective caching'
  },
  LOW_CACHE_VALUE: {
    title: 'Low Cache Value',
    description: 'Limited caching benefit: high write ratio, large size, or frequent updates.',
    recommendation: 'Cache only specific records, focus on query optimization'
  },
  NO_CACHE_BENEFIT: {
    title: 'No Cache Benefit',
    description: 'Caching not recommended: too large, write-heavy, or rarely accessed.',
    recommendation: 'Focus on database-level optimization instead'
  }
};

// =============================================================================
// SCHEMA EVOLUTION RISK
// =============================================================================
export const SCHEMA_EVOLUTION_RISK = {
  CRITICAL: {
    title: 'Critical Risk',
    description: 'Schema changes extremely risky: large table, many dependencies, heavy usage.',
    ddlImpact: 'Hours to days of downtime without careful planning',
    recommendation: 'Use online DDL tools, schedule maintenance windows, extensive testing'
  },
  HIGH: {
    title: 'High Risk',
    description: 'Schema changes require careful planning: significant size or dependencies.',
    ddlImpact: 'Minutes to hours, may require brief downtime',
    recommendation: 'Use pt-online-schema-change or gh-ost, test thoroughly'
  },
  MODERATE: {
    title: 'Moderate Risk',
    description: 'Schema changes manageable but need attention: medium size, some dependencies.',
    ddlImpact: 'Seconds to minutes',
    recommendation: 'Standard DDL with monitoring, consider off-peak timing'
  },
  LOW: {
    title: 'Low Risk',
    description: 'Schema changes low risk: small table, few dependencies.',
    ddlImpact: 'Seconds',
    recommendation: 'Standard DDL operations safe'
  },
  MINIMAL: {
    title: 'Minimal Risk',
    description: 'Schema changes trivial: tiny table, no significant dependencies.',
    ddlImpact: 'Instant',
    recommendation: 'Direct DDL operations safe anytime'
  }
};

// =============================================================================
// COST TIERS
// =============================================================================
export const COST_TIERS = {
  HIGH_COST: {
    title: 'High Cost',
    description: 'Table contributes significantly to infrastructure costs (>$100/month estimated).',
    recommendation: 'Review retention policies, consider archiving, evaluate partitioning'
  },
  MEDIUM_COST: {
    title: 'Medium Cost',
    description: 'Moderate cost contribution ($10-100/month estimated).',
    recommendation: 'Monitor growth trends, consider compression'
  },
  LOW_COST: {
    title: 'Low Cost',
    description: 'Minor cost contribution ($1-10/month estimated).',
    recommendation: 'Standard monitoring sufficient'
  },
  MINIMAL_COST: {
    title: 'Minimal Cost',
    description: 'Negligible cost contribution (<$1/month estimated).',
    recommendation: 'No cost optimization needed'
  }
};

// =============================================================================
// SHARDING READINESS
// =============================================================================
export const SHARDING_READINESS = {
  READY: {
    title: 'Sharding Ready',
    description: 'Table is well-suited for horizontal sharding: good shard key candidates, appropriate size.',
    recommendation: 'Can implement sharding when scale requires'
  },
  NEEDS_WORK: {
    title: 'Needs Work',
    description: 'Table could be sharded but requires schema modifications first.',
    recommendation: 'Address missing shard key or relationship issues before sharding'
  },
  NOT_RECOMMENDED: {
    title: 'Not Recommended',
    description: 'Table structure not suitable for sharding: cross-shard joins needed, no good shard key.',
    recommendation: 'Consider other scaling strategies (read replicas, caching)'
  },
  NOT_NEEDED: {
    title: 'Not Needed',
    description: 'Table too small to benefit from sharding.',
    recommendation: 'Standard single-node operation sufficient'
  }
};

// =============================================================================
// DATA QUALITY LEVELS
// =============================================================================
export const DATA_QUALITY_LEVELS = {
  EXCELLENT: {
    title: 'Excellent Quality',
    description: 'Data quality score 85-100. High integrity, completeness, and consistency.',
    color: 'var(--color-success)'
  },
  GOOD: {
    title: 'Good Quality',
    description: 'Data quality score 70-84. Acceptable quality with minor issues.',
    color: 'var(--color-primary)'
  },
  FAIR: {
    title: 'Fair Quality',
    description: 'Data quality score 50-69. Notable issues that should be addressed.',
    color: 'var(--color-warning)'
  },
  POOR: {
    title: 'Poor Quality',
    description: 'Data quality score 25-49. Significant quality problems affecting reliability.',
    color: 'var(--color-danger)'
  },
  CRITICAL: {
    title: 'Critical Quality',
    description: 'Data quality score 0-24. Severe issues, data may be unreliable.',
    color: 'var(--color-danger)'
  }
};

// =============================================================================
// DATA QUALITY DIMENSIONS
// =============================================================================
export const QUALITY_DIMENSIONS = {
  completeness: {
    title: 'Completeness',
    description: 'Percentage of required fields that are populated (non-null).',
    calculation: 'Non-null values / Total expected values'
  },
  uniqueness: {
    title: 'Uniqueness',
    description: 'Degree to which data is free from duplicates.',
    calculation: 'Unique values / Total values (for key columns)'
  },
  validity: {
    title: 'Validity',
    description: 'Data conforms to defined formats, ranges, and business rules.',
    calculation: 'Valid entries / Total entries'
  },
  consistency: {
    title: 'Consistency',
    description: 'Data is consistent across related tables and systems.',
    calculation: 'Matching cross-references / Total references'
  },
  timeliness: {
    title: 'Timeliness',
    description: 'Data is current and updated within expected timeframes.',
    calculation: 'Based on last update timestamps and expected freshness'
  }
};

// =============================================================================
// DEPENDENCY CRITICALITY
// =============================================================================
export const DEPENDENCY_CRITICALITY = {
  CRITICAL: {
    title: 'Critical Dependency',
    description: 'Core table that many other tables depend on. Changes have widespread impact.',
    recommendation: 'Extreme caution with changes, comprehensive testing required'
  },
  HIGH: {
    title: 'High Dependency',
    description: 'Important table with significant dependencies. Changes affect multiple systems.',
    recommendation: 'Careful change management, broad impact analysis'
  },
  MEDIUM: {
    title: 'Medium Dependency',
    description: 'Moderate dependencies. Changes have contained but notable impact.',
    recommendation: 'Standard change procedures with testing'
  },
  LOW: {
    title: 'Low Dependency',
    description: 'Few dependencies. Changes have minimal ripple effects.',
    recommendation: 'Normal change procedures sufficient'
  },
  ISOLATED: {
    title: 'Isolated',
    description: 'No detected dependencies. Table is standalone or orphaned.',
    recommendation: 'May need investigation - could be unused or missing relationships'
  }
};

// =============================================================================
// GROWTH CATEGORIES
// =============================================================================
export const GROWTH_CATEGORIES = {
  EXPLOSIVE: {
    title: 'Explosive Growth',
    description: 'Growing >50% per month. Urgent capacity planning needed.',
    recommendation: 'Immediate action: retention policies, partitioning, archiving'
  },
  RAPID: {
    title: 'Rapid Growth',
    description: 'Growing 20-50% per month. Plan for significant scaling.',
    recommendation: 'Plan storage expansion, evaluate partitioning'
  },
  STEADY: {
    title: 'Steady Growth',
    description: 'Growing 5-20% per month. Normal growth pattern.',
    recommendation: 'Quarterly capacity reviews, consider archival policies'
  },
  SLOW: {
    title: 'Slow Growth',
    description: 'Growing 1-5% per month. Minimal growth.',
    recommendation: 'Annual capacity reviews sufficient'
  },
  STABLE: {
    title: 'Stable',
    description: 'Growing <1% per month or static. Size relatively constant.',
    recommendation: 'No growth-related concerns'
  },
  SHRINKING: {
    title: 'Shrinking',
    description: 'Negative growth - table is getting smaller over time.',
    recommendation: 'Investigate if intentional (cleanup) or concerning (data loss)'
  }
};

// =============================================================================
// HEALTH SCORE
// =============================================================================
export const HEALTH_SCORE = {
  title: 'Health Score',
  description: 'Overall table health rating (0-100) based on multiple factors.',
  calculation: `
    Health Score is calculated from:
    • Schema Design (25%): Primary keys, foreign keys, normalization
    • Data Quality (25%): Completeness, validity, consistency
    • Index Coverage (25%): Appropriate indexes for query patterns
    • Maintenance (25%): Statistics freshness, bloat, fragmentation
  `,
  ranges: {
    excellent: { min: 85, max: 100, label: 'Excellent', color: 'var(--color-success)' },
    good: { min: 70, max: 84, label: 'Good', color: 'var(--color-primary)' },
    fair: { min: 50, max: 69, label: 'Fair', color: 'var(--color-warning)' },
    poor: { min: 25, max: 49, label: 'Poor', color: 'var(--color-danger)' },
    critical: { min: 0, max: 24, label: 'Critical', color: 'var(--color-danger)' }
  }
};

// =============================================================================
// BRAIN SCORE
// =============================================================================
export const BRAIN_SCORE = {
  title: 'Brain Score',
  description: 'Overall database intelligence score (0-100) measuring schema quality and optimization.',
  calculation: `
    Brain Score is calculated from:
    • Schema Design Score (25%): FK constraints, normalization, naming
    • Query Quality Score (25%): Slow queries, regressions, patterns
    • Index & Access Score (30%): Key column coverage, access patterns
    • Scalability Score (20%): Growth readiness, partitioning, sharding
  `,
  ranges: {
    excellent: { min: 85, max: 100, label: 'Excellent' },
    good: { min: 70, max: 84, label: 'Good' },
    fair: { min: 50, max: 69, label: 'Needs Improvement' },
    poor: { min: 0, max: 49, label: 'Critical' }
  }
};

// =============================================================================
// NORMAL FORMS
// =============================================================================
export const NORMAL_FORMS = {
  '1NF': {
    title: 'First Normal Form (1NF)',
    description: 'Each column contains atomic (indivisible) values. No repeating groups.',
    example: 'Instead of "tags: red,blue,green", use a separate tags table',
    violation: 'Comma-separated values, arrays in columns, repeating column groups'
  },
  '2NF': {
    title: 'Second Normal Form (2NF)',
    description: 'Meets 1NF and all non-key columns depend on the entire primary key (no partial dependencies).',
    example: 'In order_items, product_name should be in products table, not repeated',
    violation: 'Non-key column depends on only part of a composite primary key'
  },
  '3NF': {
    title: 'Third Normal Form (3NF)',
    description: 'Meets 2NF and no non-key column depends on another non-key column (no transitive dependencies).',
    example: 'customer_city depending on customer_zip should be in a separate table',
    violation: 'Non-key column depends on another non-key column'
  },
  'BCNF': {
    title: 'Boyce-Codd Normal Form (BCNF)',
    description: 'Meets 3NF and every determinant is a candidate key. Stricter than 3NF.',
    example: 'If instructor determines course and course determines room, normalize',
    violation: 'A non-candidate-key column determines another column'
  },
  '4NF': {
    title: 'Fourth Normal Form (4NF)',
    description: 'Meets BCNF and has no multi-valued dependencies.',
    example: 'Student skills and languages should be in separate tables, not combined',
    violation: 'Independent multi-valued facts about an entity in one table'
  },
  '5NF': {
    title: 'Fifth Normal Form (5NF)',
    description: 'Meets 4NF and cannot be decomposed into smaller tables without data loss.',
    example: 'Complex relationships requiring three-way joins to reconstruct',
    violation: 'Join dependencies not implied by candidate keys'
  }
};

// =============================================================================
// SENSITIVITY LEVELS
// =============================================================================
export const SENSITIVITY_LEVELS = {
  PUBLIC: {
    title: 'Public',
    description: 'Non-sensitive data that can be freely shared.',
    handling: 'No special handling required'
  },
  INTERNAL: {
    title: 'Internal',
    description: 'Business data for internal use only.',
    handling: 'Access logging recommended'
  },
  CONFIDENTIAL: {
    title: 'Confidential',
    description: 'Sensitive business data requiring access controls.',
    handling: 'Role-based access, audit logging required'
  },
  PII: {
    title: 'PII (Personal Identifiable Information)',
    description: 'Personal data protected by privacy regulations (GDPR, CCPA).',
    handling: 'Encryption, access controls, audit logging, retention policies required'
  },
  PHI: {
    title: 'PHI (Protected Health Information)',
    description: 'Health data protected by HIPAA regulations.',
    handling: 'Strict encryption, access controls, audit trails required'
  },
  FINANCIAL: {
    title: 'Financial Data',
    description: 'Payment and financial information requiring PCI compliance.',
    handling: 'Encryption at rest/transit, tokenization, strict access controls'
  }
};

// =============================================================================
// OVERVIEW METRICS
// =============================================================================
export const OVERVIEW_METRICS = {
  schemaPattern: {
    title: 'Schema Pattern',
    description: 'The detected overall design pattern of your database schema based on table relationships and roles.',
    patterns: {
      STAR: 'Central fact tables directly connected to dimension tables. Optimized for query performance.',
      SNOWFLAKE: 'Normalized dimensions with sub-dimensions. Reduces redundancy but requires more joins.',
      HYBRID: 'Mix of star and snowflake patterns, balancing performance and normalization.',
      OLTP: 'Transactional schema optimized for real-time operations rather than analytics.',
      FLAT: 'Minimal relationships between tables. May indicate missing foreign keys.'
    }
  },
  confidenceScore: {
    title: 'Confidence Score',
    description: 'How confident the analysis is about the detected schema pattern (0-100%).',
    calculation: 'Based on the clarity of fact/dimension roles, relationship completeness, and pattern consistency.',
    interpretation: {
      high: '80-100%: Strong pattern match, clear schema design',
      medium: '50-79%: Moderate confidence, some ambiguity in design',
      low: '0-49%: Low confidence, schema may be mixed or incomplete'
    }
  },
  avgHealthScore: {
    title: 'Average Health Score',
    description: 'Mean health score across all analyzed tables (0-100%).',
    calculation: 'Average of individual table health scores weighted by table importance.',
    factors: 'Index efficiency, data quality, constraint coverage, and maintenance status.'
  },
  totalTables: {
    title: 'Total Tables',
    description: 'Number of tables analyzed in this database schema.',
    note: 'Includes all user tables; system tables are typically excluded.'
  },
  factTables: {
    title: 'Fact Tables',
    description: 'Tables identified as containing measurable, quantitative data (metrics, transactions, events).',
    characteristics: 'High row counts, numeric measures, foreign keys to dimensions, time-based data.',
    examples: 'orders, transactions, page_views, sales, bookings'
  },
  dimensionTables: {
    title: 'Dimension Tables',
    description: 'Tables containing descriptive attributes used to filter, group, or label fact data.',
    characteristics: 'Lower row counts, descriptive text, referenced by fact tables.',
    examples: 'customers, products, locations, dates, categories'
  },
  antiPatternCount: {
    title: 'Tables with Anti-Patterns',
    description: 'Number of tables that have detected schema design issues.',
    impact: 'Anti-patterns can cause performance problems, data integrity issues, or maintenance difficulties.',
    action: 'Review the Anti-Patterns tab for details and recommendations.'
  },
  piiTables: {
    title: 'PII Tables',
    description: 'Tables containing Personally Identifiable Information requiring special handling.',
    examples: 'Names, emails, phone numbers, addresses, SSN, financial data.',
    compliance: 'May be subject to GDPR, CCPA, HIPAA, or other privacy regulations.'
  },
  partitionCandidates: {
    title: 'Partition Candidates',
    description: 'Large tables that would benefit from partitioning for better performance.',
    benefits: 'Faster queries on recent data, easier maintenance, improved backup/restore times.',
    criteria: 'Tables with >1M rows, time-based data, or identified growth patterns.'
  },
  circularDependencies: {
    title: 'Circular Dependencies',
    description: 'Tables that reference each other in a loop (A→B→C→A), which can cause issues.',
    problems: 'Difficult inserts/deletes, cascading update issues, ORM complications.',
    recommendation: 'Review relationships and consider breaking the cycle with nullable FKs or restructuring.'
  }
};

// =============================================================================
// SCHEMA CLASSIFICATION TABS
// =============================================================================
export const CLASSIFICATION_TABS = {
  overview: {
    title: 'Overview',
    description: 'Complete list of all tables with their classifications, roles, and relationship metrics.',
  },
  health: {
    title: 'Health Analysis',
    description: 'Tables sorted by health score showing index efficiency, data quality, and maintenance status.',
  },
  access: {
    title: 'Access Patterns',
    description: 'How tables are being used: read-heavy, write-heavy, append-only, or mixed workloads.',
  },
  antipatterns: {
    title: 'Anti-Patterns',
    description: 'Detected schema design issues like wide tables, missing keys, or god tables that need attention.',
  },
  domains: {
    title: 'Business Domains',
    description: 'Tables categorized by business function: customer, product, transaction, analytics, etc.',
  },
  sensitivity: {
    title: 'Data Sensitivity',
    description: 'Tables containing PII, financial, health, or other sensitive data requiring special handling.',
  },
  partitioning: {
    title: 'Partitioning Candidates',
    description: 'Large tables that would benefit from partitioning for improved query performance and maintenance.',
  }
};

// =============================================================================
// QUERY INTELLIGENCE (Brain 2.0)
// =============================================================================
export const QUERY_INTELLIGENCE = {
  // Panel Header
  panel: {
    title: 'Query Intelligence',
    description: 'ML-based learning system that builds pattern knowledge over time for smarter query optimization.',
    differentiation: 'Unlike the Slow Queries tab (operational monitoring for immediate issues), Query Intelligence focuses on long-term pattern recognition and cardinality accuracy learning.',
  },

  // Buttons
  refreshButton: {
    title: 'Refresh Data',
    description: 'Reload cardinality accuracy and plan patterns from the database.',
  },

  // Tabs
  // Note: Column Statistics tab removed - data consolidated with Key Column Analysis in Overview tab
  accuracyTab: {
    title: 'Cardinality Accuracy',
    description: 'Measures how accurately the database estimates row counts for query planning.',
    impact: 'Poor cardinality estimates lead to suboptimal query plans - wrong join orders, missing indexes.',
    recommendation: 'Tables with low accuracy (<70%) may need updated statistics or histogram analysis.',
  },
  patternsTab: {
    title: 'Plan Patterns',
    description: 'Query execution patterns learned from repeated queries. Patterns are normalized (with placeholders for literals) to group similar queries together.',
    impact: 'Patterns with high confidence represent well-optimized queries that consistently perform well.',
    recommendation: 'Low-confidence patterns may indicate queries with variable performance - candidates for optimization.',
    differentiation: 'Unlike individual slow query optimization in Database Advisor, Plan Patterns show aggregated insights across all instances of a query pattern.',
  },

  // Cardinality Accuracy Fields
  accuracy: {
    overallAccuracy: {
      title: 'Overall Accuracy',
      description: 'Average accuracy of row count estimates across all queries.',
      recommendation: 'Target >90%. Below 70% indicates stale statistics or complex query patterns.',
    },
    accurateEstimates: {
      title: 'Accurate Estimates',
      description: 'Number of queries where the estimated row count was within acceptable range of actual.',
      recommendation: 'Higher is better. Low count may indicate need for ANALYZE or statistics refresh.',
    },
    overestimates: {
      title: 'Overestimates',
      description: 'Queries where the optimizer predicted more rows than actually returned.',
      impact: 'May cause unnecessarily complex plans, extra memory allocation.',
      recommendation: 'Often caused by outdated statistics or skewed data distribution.',
    },
    underestimates: {
      title: 'Underestimates',
      description: 'Queries where the optimizer predicted fewer rows than actually returned.',
      impact: 'Can cause severe performance issues - nested loops instead of hash joins, memory spills.',
      recommendation: 'Most dangerous type. Update statistics and consider histogram creation.',
    },
    perTableAccuracy: {
      title: 'Accuracy by Table',
      description: 'Cardinality estimation accuracy broken down by table.',
      recommendation: 'Focus on tables with <70% accuracy - they likely need ANALYZE or index optimization.',
    },
    recommendations: {
      title: 'Recommendations',
      description: 'Actionable database maintenance commands to improve cardinality estimation accuracy.',
      differentiation: 'These focus on optimizer statistics maintenance (ANALYZE TABLE, histograms). Query rewrites and index suggestions are in Database Advisor → Slow Queries.',
      impact: 'Accurate cardinality estimates lead to better query plans, reducing execution time for complex queries.',
    },
    analyzeTable: {
      title: 'ANALYZE TABLE',
      description: 'Refreshes table statistics so the MySQL optimizer has accurate row count data for query planning.',
      impact: 'After ANALYZE, the optimizer makes better decisions about join order, index usage, and memory allocation.',
    },
    histogram: {
      title: 'Histogram',
      description: 'MySQL 8.0+ feature that captures data distribution for columns with skewed values.',
      impact: 'Histograms help the optimizer understand when a column value is rare (highly selective) vs common.',
      recommendation: 'Create histograms on columns used in WHERE clauses that have non-uniform data distribution.',
    },
  },

  // Plan Patterns Fields
  patterns: {
    totalPatterns: {
      title: 'Total Patterns',
      description: 'Number of distinct query execution patterns observed.',
    },
    reliablePatterns: {
      title: 'Reliable Patterns',
      description: 'Patterns with high confidence (>80%) that consistently produce good results.',
      recommendation: 'Reliable patterns indicate well-optimized, stable queries.',
    },
    avgConfidence: {
      title: 'Average Confidence',
      description: 'Mean confidence score across all patterns. Higher means more predictable query performance.',
      recommendation: 'Target >70%. Low average indicates variable query performance.',
    },
    patternType: {
      title: 'Pattern Type',
      description: 'Category of the query pattern (e.g., index scan, hash join, sequential scan).',
    },
    confidence: {
      title: 'Pattern Confidence',
      description: 'How reliably this pattern produces optimal results. Based on historical execution consistency.',
      recommendation: 'Low confidence (<60%) patterns are candidates for query tuning.',
    },
    observationCount: {
      title: 'Observations',
      description: 'Number of times this pattern has been observed. More observations = more reliable confidence.',
    },
    queryTemplate: {
      title: 'Query Template',
      description: 'The normalized query structure that matches this pattern.',
    },
    recommendedPlan: {
      title: 'Recommended Plan',
      description: 'The optimal execution plan for queries matching this pattern.',
    },
  },
};

// =============================================================================
// WORKLOAD INTELLIGENCE (Brain 2.0)
// =============================================================================
export const WORKLOAD_INTELLIGENCE = {
  // Panel Header
  panel: {
    title: 'Workload Intelligence',
    description: 'ML-powered analysis of your database workload patterns. Identifies whether your database is optimized for transactions (OLTP), analytics (OLAP), or mixed workloads.',
  },

  // Buttons
  refreshButton: {
    title: 'Refresh Data',
    description: 'Reload the current learning progress and workload profile.',
  },
  characterizeButton: {
    title: 'Analyze Now',
    description: 'Immediately analyze collected metrics to classify your workload type. Normally runs automatically every 4 hours.',
    impact: 'Creates a workload fingerprint used for classification and optimization recommendations.',
    recommendation: 'Use when you want immediate results instead of waiting for automatic analysis.',
  },

  // Workload Types
  workloadTypes: {
    OLTP: {
      title: 'OLTP (Online Transaction Processing)',
      description: 'Transaction-heavy workload optimized for fast writes, point queries, and high concurrency.',
      optimization: 'Focus on low latency, connection pooling, and index optimization for primary key lookups.',
      examples: 'E-commerce orders, banking transactions, user sessions',
    },
    OLAP: {
      title: 'OLAP (Online Analytical Processing)',
      description: 'Analytics-heavy workload optimized for complex queries, aggregations, and large scans.',
      optimization: 'Focus on query parallelism, columnar storage, materialized views, and large buffer pools.',
      examples: 'Business intelligence, reporting dashboards, data warehousing',
    },
    MIXED: {
      title: 'Mixed Workload',
      description: 'Balanced mix of read and write operations without a clear dominant pattern.',
      optimization: 'Balance configuration for both read and write performance. Monitor for shifting patterns.',
      examples: 'General-purpose applications, CMS systems, SaaS platforms',
    },
    HYBRID: {
      title: 'Hybrid Workload',
      description: 'Mixed workload with both transactional and analytical patterns running concurrently.',
      optimization: 'Balance read/write optimization. Consider read replicas for analytics or HTAP solutions.',
      examples: 'Real-time analytics on operational data, mixed CRUD and reporting',
    },
    READ_HEAVY: {
      title: 'Read-Heavy Workload',
      description: 'Workload dominated by read operations (SELECT queries) with infrequent writes.',
      optimization: 'Optimize for caching, read replicas, and query result caching. Index heavily used columns.',
      examples: 'Content delivery, product catalogs, reference data lookups',
    },
    WRITE_HEAVY: {
      title: 'Write-Heavy Workload',
      description: 'Workload dominated by write operations (INSERT, UPDATE, DELETE).',
      optimization: 'Optimize for write throughput, consider bulk inserts, tune checkpoint frequency.',
      examples: 'Logging systems, IoT data ingestion, real-time event capture',
    },
    BATCH: {
      title: 'Batch Processing',
      description: 'Scheduled bulk operations with large data volumes processed periodically.',
      optimization: 'Focus on bulk insert optimization, parallel processing, and off-peak scheduling.',
      examples: 'ETL jobs, nightly data imports, scheduled reports',
    },
  },

  // Metrics
  metrics: {
    queriesPerSecond: {
      title: 'Queries/sec (QPS)',
      description: 'Number of SQL queries executed per second. Higher QPS indicates more active workload.',
      recommendation: 'Monitor for sudden spikes or drops that may indicate issues.',
    },
    transactionsPerSecond: {
      title: 'Transactions/sec (TPS)',
      description: 'Number of committed transactions per second. Key metric for OLTP workloads.',
      recommendation: 'Target consistent TPS. High variance may indicate lock contention.',
    },
    avgLatency: {
      title: 'Average Latency',
      description: 'Mean query execution time across all queries.',
      recommendation: 'Keep under 100ms for OLTP, under 5s for OLAP queries.',
    },
    p95Latency: {
      title: 'P95 Latency',
      description: '95th percentile query latency. 95% of queries complete faster than this time.',
      recommendation: 'Monitor for tail latency issues. Should be within 2-3x of average.',
    },
    readWriteRatio: {
      title: 'Read/Write Ratio',
      description: 'Percentage of read operations vs write operations.',
      recommendation: 'High read ratio (>80%) suggests caching opportunities. High write ratio needs write optimization.',
    },
    cacheHitRatio: {
      title: 'Cache Hit Ratio',
      description: 'Percentage of queries served from buffer cache vs disk.',
      recommendation: 'Target >95% for OLTP. Low ratio suggests insufficient buffer pool or working set too large.',
    },
  },

  // Sections
  workloadFeatures: {
    title: 'Key Metrics (Live Counters)',
    description: 'Real-time performance metrics from database system tables (SHOW GLOBAL STATUS, pg_stat_*). These are live counters, not historical data.',
    impact: 'These metrics form the workload fingerprint used for ML classification. They reflect current/recent database activity.',
    recommendation: 'For historical slow query analysis, use Performance → Slow Query Analysis which shows ingested log data.',
  },
  similarWorkloads: {
    title: 'Similar Workloads',
    description: 'Other workload profiles with similar characteristics. Useful for finding optimization strategies that worked for comparable workloads.',
    recommendation: 'Review tuning configurations from highly similar workloads for optimization ideas.',
  },
  confidence: {
    title: 'Confidence Score',
    description: 'How confident the ML model is about the workload classification (0-100%).',
    recommendation: 'Low confidence (<70%) suggests mixed or unusual workload. Consider re-characterizing with more data.',
  },

  // Individual Key Metrics (displayed in profile)
  keyMetrics: {
    slow_queries: {
      title: 'Slow Queries (Live Counter)',
      description: 'MySQL\'s live counter of queries exceeding long_query_time since server start. This is NOT your ingested slow query logs.',
      impact: 'A low number here doesn\'t mean you have no slow queries - check the Slow Query Analysis tab for ingested historical data.',
      recommendation: 'This metric helps classify workload patterns. For detailed slow query analysis, use the Performance → Slow Query Analysis tab.',
    },
    cache_hit_ratio: {
      title: 'Buffer Pool Cache Hit Ratio',
      description: 'Percentage of data reads served from memory (buffer pool) vs disk. Calculated as: (read_requests - disk_reads) / read_requests.',
      impact: 'Higher is better. Below 95% indicates your working set may not fit in memory, causing disk I/O.',
      recommendation: 'If below 95%, consider increasing innodb_buffer_pool_size or optimizing queries to reduce data scanned.',
    },
    innodb_buffer_hit_ratio: {
      title: 'InnoDB Buffer Hit Ratio',
      description: 'Same as cache_hit_ratio - percentage of InnoDB page reads served from buffer pool vs disk.',
      impact: 'Target >99% for OLTP workloads. Lower values cause slower queries due to disk I/O.',
      recommendation: 'Increase innodb_buffer_pool_size to 70-80% of available RAM on dedicated DB servers.',
    },
    read_write_ratio: {
      title: 'Read/Write Ratio',
      description: 'Ratio of SELECT queries to write operations (INSERT + UPDATE + DELETE). Higher means more read-heavy.',
      impact: 'Helps classify workload: >10 = read-heavy, <0.5 = write-heavy, 0.5-10 = mixed.',
      recommendation: 'Read-heavy workloads benefit from read replicas and caching. Write-heavy needs write optimization.',
    },
    commit_ratio: {
      title: 'Commit Ratio',
      description: 'Percentage of transactions that commit successfully vs rollback. Calculated as: commits / (commits + rollbacks).',
      impact: 'Low ratio (<95%) may indicate application errors, deadlocks, or lock timeouts.',
      recommendation: 'Investigate application logs and deadlock monitoring if ratio is consistently low.',
    },
    index_usage_ratio: {
      title: 'Index Usage Ratio',
      description: 'Percentage of queries using indexes vs full table scans.',
      impact: 'Low ratio means many queries are scanning entire tables, causing slow performance.',
      recommendation: 'Review slow queries and add indexes on frequently filtered/joined columns.',
    },
    connections_active: {
      title: 'Active Connections',
      description: 'Number of database connections currently executing queries (not idle).',
      impact: 'High active connections may indicate slow queries blocking others or connection pool issues.',
      recommendation: 'Monitor for spikes. Consider connection pooling if consistently high.',
    },
    threads_running: {
      title: 'Threads Running',
      description: 'MySQL threads actively processing queries (not sleeping).',
      impact: 'Sustained high values (>CPU cores) indicate CPU saturation or lock contention.',
      recommendation: 'Target < number of CPU cores. High values need query optimization or hardware scaling.',
    },
    tmp_disk_tables_ratio: {
      title: 'Temp Disk Tables Ratio',
      description: 'Percentage of temporary tables created on disk vs memory. Disk temp tables are much slower.',
      impact: 'High ratio (>25%) indicates queries creating large temp tables that don\'t fit in memory.',
      recommendation: 'Increase tmp_table_size and max_heap_table_size, or optimize queries with large GROUP BY/ORDER BY.',
    },
  },

  // Collected Metrics
  collectedMetrics: {
    title: 'Workload Metrics Collected',
    description: 'Database performance counters and statistics collected every 15 minutes to build your workload profile.',
    details: {
      mysql: {
        title: 'MySQL Metrics (~45)',
        categories: [
          { name: 'Throughput', metrics: 'Bytes_received, Bytes_sent, Queries, Questions' },
          { name: 'Operations', metrics: 'Com_select, Com_insert, Com_update, Com_delete, Com_commit, Com_rollback' },
          { name: 'Connections', metrics: 'Threads_connected, Threads_running, Max_used_connections' },
          { name: 'InnoDB Buffer', metrics: 'Innodb_buffer_pool_read_requests, Innodb_buffer_pool_reads, pages_total/free/dirty' },
          { name: 'InnoDB Rows', metrics: 'Innodb_rows_read, Innodb_rows_inserted, Innodb_rows_updated, Innodb_rows_deleted' },
          { name: 'InnoDB I/O', metrics: 'Innodb_data_reads, Innodb_data_writes, Innodb_log_writes' },
          { name: 'Locking', metrics: 'Innodb_row_lock_waits, Innodb_row_lock_time, Innodb_deadlocks' },
          { name: 'Temp Tables', metrics: 'Created_tmp_tables, Created_tmp_disk_tables' },
          { name: 'Calculated', metrics: 'innodb_buffer_hit_ratio, read_write_ratio' },
        ],
      },
      postgresql: {
        title: 'PostgreSQL Metrics (~55)',
        categories: [
          { name: 'Database Stats', metrics: 'xact_commit, xact_rollback, blks_read, blks_hit, tup_returned, tup_fetched' },
          { name: 'Tuple Operations', metrics: 'tup_inserted, tup_updated, tup_deleted, deadlocks, conflicts' },
          { name: 'Background Writer', metrics: 'checkpoints_timed, checkpoints_req, buffers_checkpoint, buffers_clean' },
          { name: 'Connections', metrics: 'numbackends, active, idle, idle_in_transaction' },
          { name: 'Query Stats', metrics: 'total_time, calls, rows, shared_blks_hit/read, temp_blks_read/written' },
          { name: 'Locks', metrics: 'lock counts by mode (AccessShare, RowExclusive, etc.)' },
          { name: 'Calculated', metrics: 'cache_hit_ratio, commit_ratio, read_write_ratio' },
        ],
      },
    },
    recommendation: 'More snapshots = more accurate workload characterization. The system needs at least 10 snapshots for reliable classification.',
  },
};

// =============================================================================
// CONFIG TUNING (Brain 2.0)
// =============================================================================
export const CONFIG_TUNING = {
  // Panel Header
  panel: {
    title: 'Config Tuning',
    description: 'ML-powered analysis of database configuration parameters. Identifies which settings have the most impact on your workload and provides AI-generated tuning recommendations.',
  },

  // Buttons
  refreshButton: {
    title: 'Refresh Data',
    description: 'Reload knob rankings and experiment data from the database.',
  },
  identifyKnobsButton: {
    title: 'Identify Knobs',
    description: 'Scan your database configuration and rank parameters by their performance impact.',
    impact: 'Analyzes settings like buffer_pool_size, max_connections, work_mem to find which ones matter most for your workload.',
    recommendation: 'Run this after significant workload changes or when troubleshooting performance issues.',
  },
  getRecommendationsButton: {
    title: 'Get Recommendations',
    description: 'Generate AI-powered tuning suggestions based on your current workload patterns.',
    impact: 'Analyzes query patterns, resource usage, and current settings to suggest optimal values.',
    recommendation: 'Run after identifying knobs to get specific value recommendations with expected improvements.',
  },
  createExperimentButton: {
    title: 'Create Experiment',
    description: 'Track a configuration change as an experiment to validate its impact before making it permanent.',
    impact: 'Creates a record of the change with before/after values for comparison.',
    recommendation: 'Use experiments to safely test tuning changes and measure actual performance gains.',
  },
  testButton: {
    title: 'Test Recommendation',
    description: 'Create an experiment to validate this recommendation before applying it permanently.',
    impact: 'Tracks the configuration change and measures real performance impact.',
    recommendation: 'Always test recommendations on staging or during low-traffic periods first.',
  },

  // Tabs
  knobsTab: {
    title: 'Knob Rankings',
    description: 'Database configuration parameters ranked by their impact on performance.',
    impact: 'Higher impact knobs have more influence on query speed, memory usage, and overall throughput.',
    recommendation: 'Focus tuning efforts on high-impact knobs first for maximum benefit.',
    examples: 'innodb_buffer_pool_size, shared_buffers, work_mem',
  },
  recommendationsTab: {
    title: 'ML Recommendations',
    description: 'AI-generated tuning suggestions based on your specific workload patterns.',
    impact: 'Each recommendation includes the expected performance improvement percentage.',
    recommendation: 'Review the reasoning for each suggestion and test changes via experiments.',
  },
  experimentsTab: {
    title: 'Experiments',
    description: 'Track and validate configuration changes before making them permanent.',
    impact: 'Experiments show original value, target value, and measured results.',
    recommendation: 'Create experiments from knob rankings or recommendations to safely test changes.',
  },

  // Impact Levels
  impactLevels: {
    CRITICAL: {
      title: 'Critical Impact',
      description: 'This parameter has the highest impact on database performance (80%+ impact score).',
      recommendation: 'Prioritize tuning this parameter. Changes can significantly affect query latency and throughput.',
    },
    HIGH: {
      title: 'High Impact',
      description: 'This parameter significantly affects performance (50-79% impact score).',
      recommendation: 'Consider tuning after addressing critical parameters.',
    },
    MEDIUM: {
      title: 'Medium Impact',
      description: 'This parameter has moderate performance impact (30-49% impact score).',
      recommendation: 'Tune as part of overall optimization efforts.',
    },
    LOW: {
      title: 'Low Impact',
      description: 'This parameter has minimal performance impact (<30% impact score).',
      recommendation: 'Default values are usually sufficient.',
    },
  },

  // Experiment Statuses
  experimentStatuses: {
    PENDING: {
      title: 'Pending',
      description: 'Experiment created but not yet executed.',
      recommendation: 'Apply the configuration change and run your workload to measure impact.',
    },
    RUNNING: {
      title: 'Running',
      description: 'Experiment is in progress, collecting performance metrics.',
      recommendation: 'Wait for sufficient data before drawing conclusions.',
    },
    COMPLETED: {
      title: 'Completed',
      description: 'Experiment finished with measured results.',
      recommendation: 'Review the improvement percentage to decide if the change should be permanent.',
    },
    FAILED: {
      title: 'Failed',
      description: 'Experiment encountered an error or was rolled back.',
      recommendation: 'Review logs and consider adjusting the target value.',
    },
  },
};

// =============================================================================
// HELPER FUNCTION TO GET HELP TEXT
// =============================================================================
export const getHelpText = (category, key) => {
  const categories = {
    tableRoles: TABLE_ROLES,
    schemaPatterns: SCHEMA_PATTERNS,
    antiPatterns: ANTI_PATTERNS,
    severityLevels: SEVERITY_LEVELS,
    accessPatterns: ACCESS_PATTERNS,
    dataLifecycle: DATA_LIFECYCLE,
    cacheAffinity: CACHE_AFFINITY,
    schemaEvolutionRisk: SCHEMA_EVOLUTION_RISK,
    costTiers: COST_TIERS,
    shardingReadiness: SHARDING_READINESS,
    dataQualityLevels: DATA_QUALITY_LEVELS,
    qualityDimensions: QUALITY_DIMENSIONS,
    dependencyCriticality: DEPENDENCY_CRITICALITY,
    growthCategories: GROWTH_CATEGORIES,
    normalForms: NORMAL_FORMS,
    sensitivityLevels: SENSITIVITY_LEVELS
  };

  const categoryData = categories[category];
  if (!categoryData) return null;

  return categoryData[key] || null;
};

export default {
  TABLE_ROLES,
  SCHEMA_PATTERNS,
  ANTI_PATTERNS,
  SEVERITY_LEVELS,
  ACCESS_PATTERNS,
  DATA_LIFECYCLE,
  CACHE_AFFINITY,
  SCHEMA_EVOLUTION_RISK,
  COST_TIERS,
  SHARDING_READINESS,
  DATA_QUALITY_LEVELS,
  QUALITY_DIMENSIONS,
  DEPENDENCY_CRITICALITY,
  GROWTH_CATEGORIES,
  HEALTH_SCORE,
  BRAIN_SCORE,
  NORMAL_FORMS,
  SENSITIVITY_LEVELS,
  OVERVIEW_METRICS,
  CLASSIFICATION_TABS,
  QUERY_INTELLIGENCE,
  WORKLOAD_INTELLIGENCE,
  CONFIG_TUNING,
  getHelpText
};
