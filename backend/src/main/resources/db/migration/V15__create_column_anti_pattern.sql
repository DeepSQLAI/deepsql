-- Create column_anti_pattern table for storing detected anti-patterns with actionable recommendations
-- Helps DBAs identify and fix performance issues proactively

create table if not exists column_anti_pattern (
    id varchar(36) primary key,
    connection_id varchar(255) not null,
    table_name varchar(255) not null,
    column_name varchar(255) not null,

    -- Anti-pattern classification
    pattern_type varchar(100) not null,
    severity varchar(20) not null,

    -- Problem description
    title varchar(255) not null,
    description text not null,

    -- Evidence
    evidence_count integer default 1,
    sample_query_ids text,

    -- Impact metrics
    affected_queries_count integer,
    total_execution_time_ms bigint,
    estimated_impact_score decimal(5,2),

    -- Recommendation
    recommendation text,
    suggested_index text,

    -- Status
    status varchar(50) default 'ACTIVE',
    acknowledged_at timestamp,
    acknowledged_by varchar(255),
    resolved_at timestamp,

    -- Timestamps
    detected_at timestamp not null,
    created_at timestamp not null,
    updated_at timestamp,

    constraint uk_col_anti_pattern unique (connection_id, table_name, column_name, pattern_type)
);

create index if not exists idx_col_anti_pattern_connection on column_anti_pattern(connection_id);
create index if not exists idx_col_anti_pattern_column on column_anti_pattern(connection_id, table_name, column_name);
create index if not exists idx_col_anti_pattern_severity on column_anti_pattern(severity);
create index if not exists idx_col_anti_pattern_status on column_anti_pattern(status);
create index if not exists idx_col_anti_pattern_type on column_anti_pattern(pattern_type);
