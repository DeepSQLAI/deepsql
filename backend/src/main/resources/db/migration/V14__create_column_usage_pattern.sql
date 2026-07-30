-- Create column_usage_pattern table for storing detailed breakdown of column usage occurrences
-- Enables drill-down analysis into specific queries using each column

create table if not exists column_usage_pattern (
    id varchar(36) primary key,
    connection_id varchar(255) not null,
    table_name varchar(255) not null,
    column_name varchar(255) not null,

    -- Usage context
    usage_type varchar(50) not null,
    operation varchar(100),

    -- Query source
    query_source varchar(50) not null,
    query_id varchar(255),
    query_text text,
    query_hash varchar(64),

    -- Query metadata
    query_execution_time_ms decimal(10,2),
    query_first_seen timestamp,
    query_last_seen timestamp,
    occurrence_count integer default 1,

    -- Timestamps
    created_at timestamp not null,
    updated_at timestamp,

    constraint uk_col_usage_pattern unique (connection_id, table_name, column_name, query_hash, usage_type)
);

create index if not exists idx_col_usage_connection on column_usage_pattern(connection_id);
create index if not exists idx_col_usage_column on column_usage_pattern(connection_id, table_name, column_name);
create index if not exists idx_col_usage_type on column_usage_pattern(usage_type);
create index if not exists idx_col_usage_source on column_usage_pattern(query_source);
create index if not exists idx_col_usage_hash on column_usage_pattern(query_hash);
create index if not exists idx_col_usage_created on column_usage_pattern(created_at);
