-- Create key_column_analysis table for storing aggregated importance scores per column
-- Tracks column usage patterns across JOINs, WHERE clauses, GROUP BY, and ORDER BY operations

create table if not exists key_column_analysis (
    id varchar(36) primary key,
    connection_id varchar(255) not null,
    table_name varchar(255) not null,
    column_name varchar(255) not null,

    -- Usage counts by type
    join_count integer default 0,
    where_count integer default 0,
    group_by_count integer default 0,
    order_by_count integer default 0,
    total_usage_count integer default 0,

    -- Weighted importance score (0-100)
    importance_score decimal(5,2) default 0.0,

    -- Query source breakdown
    slow_query_usage integer default 0,
    lineage_usage integer default 0,
    performance_history_usage integer default 0,

    -- Metadata for future frequency/recency weighting
    first_seen_at timestamp,
    last_seen_at timestamp,
    distinct_queries_count integer default 0,

    -- Cardinality info (denormalized from column_profile)
    distinct_count bigint,
    total_rows bigint,
    selectivity decimal(5,4),

    -- Anti-pattern flags
    has_anti_patterns boolean default false,
    anti_pattern_count integer default 0,

    -- Timestamps
    analyzed_at timestamp not null,
    created_at timestamp not null,
    updated_at timestamp,

    constraint uk_key_col_analysis unique (connection_id, table_name, column_name)
);

create index if not exists idx_key_col_connection on key_column_analysis(connection_id);
create index if not exists idx_key_col_score on key_column_analysis(connection_id, importance_score desc);
create index if not exists idx_key_col_table on key_column_analysis(connection_id, table_name);
create index if not exists idx_key_col_anti_patterns on key_column_analysis(connection_id, has_anti_patterns);
create index if not exists idx_key_col_analyzed_at on key_column_analysis(analyzed_at);
