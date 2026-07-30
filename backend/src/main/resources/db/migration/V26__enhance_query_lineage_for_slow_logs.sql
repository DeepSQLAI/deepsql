-- Enhance query_lineage to capture slow query log metadata and sources

create table if not exists query_lineage (
    id varchar(36) primary key,
    connection_id varchar(255) not null,
    source varchar(100) not null,
    query_text text not null,
    query_hash varchar(64),
    normalized_query text,
    tables_used text,
    columns_used text,
    avg_execution_time_ms double precision,
    max_execution_time_ms double precision,
    min_execution_time_ms double precision,
    total_execution_time_ms double precision,
    call_count bigint,
    rows_examined bigint,
    rows_sent bigint,
    severity varchar(32),
    performance_impact double precision,
    first_seen_at timestamp,
    last_seen_at timestamp,
    analysis_id varchar(36),
    analysis_time_range varchar(50),
    source_details text,
    created_at timestamp not null default now()
);

alter table query_lineage add column if not exists source varchar(100);
alter table query_lineage add column if not exists query_text text;
alter table query_lineage add column if not exists query_hash varchar(64);
alter table query_lineage add column if not exists normalized_query text;
alter table query_lineage add column if not exists tables_used text;
alter table query_lineage add column if not exists columns_used text;
alter table query_lineage add column if not exists avg_execution_time_ms double precision;
alter table query_lineage add column if not exists max_execution_time_ms double precision;
alter table query_lineage add column if not exists min_execution_time_ms double precision;
alter table query_lineage add column if not exists total_execution_time_ms double precision;
alter table query_lineage add column if not exists call_count bigint;
alter table query_lineage add column if not exists rows_examined bigint;
alter table query_lineage add column if not exists rows_sent bigint;
alter table query_lineage add column if not exists severity varchar(32);
alter table query_lineage add column if not exists performance_impact double precision;
alter table query_lineage add column if not exists first_seen_at timestamp;
alter table query_lineage add column if not exists last_seen_at timestamp;
alter table query_lineage add column if not exists analysis_id varchar(36);
alter table query_lineage add column if not exists analysis_time_range varchar(50);
alter table query_lineage add column if not exists source_details text;
alter table query_lineage add column if not exists created_at timestamp;

create index if not exists idx_query_lineage_connection on query_lineage(connection_id);
create index if not exists idx_query_lineage_created on query_lineage(created_at);
create index if not exists idx_query_lineage_source on query_lineage(source);
create index if not exists idx_query_lineage_query_hash on query_lineage(query_hash);
create index if not exists idx_query_lineage_analysis on query_lineage(analysis_id);

-- Backfill source from legacy query_type column if it exists
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'query_lineage'
          AND column_name = 'query_type'
    ) THEN
        EXECUTE 'UPDATE query_lineage SET source = COALESCE(source, query_type) WHERE source IS NULL';
    END IF;
END $$;

update query_lineage set created_at = now() where created_at is null;
