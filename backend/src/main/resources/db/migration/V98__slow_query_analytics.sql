-- Slow-query analytics upgrade — turn the slow-query module into a real
-- 30-day analytical store instead of a 20-entry JSON-blob log.
--
-- Why:
--   The old `slow_query_history` table kept each analysis run as one JSON
--   blob, capped at 20 rows per connection. You could not trend a single
--   query over time, compare runs, or attribute load to a customer.
--
-- This migration introduces a small star schema:
--   * slow_query_run            — per-query × per-day fact (the time series)
--   * slow_query_customer       — customer dimension (id → resolved name)
--   * slow_query_sample         — literal-bearing executions from the slow log
--   * slow_query_customer_day   — per-query × per-customer × per-day rollup
--   * connection_analytics_config — per-connection tenant-column + name lookup
--
-- The stable query identity is the existing MD5 fingerprint
-- (query_fingerprints.fingerprint). It is deterministic — the same
-- normalized query always hashes to the same value — so a query keeps one
-- ID across all 30 days of runs. `normalization_version` guards against a
-- future change to the normalization rules silently forking identities.

-- ── query identity: version the normalization so IDs stay stable ──────────
ALTER TABLE query_fingerprints
    ADD COLUMN IF NOT EXISTS normalization_version INTEGER NOT NULL DEFAULT 1;

-- ── retention: replace the hard "keep 20" cap with a configurable window ──
ALTER TABLE resource_limits
    ADD COLUMN IF NOT EXISTS slow_query_history_retention_days INTEGER NOT NULL DEFAULT 30;

-- ── per-connection analytics config ───────────────────────────────────────
-- One tenant column per connection (e.g. hotel_id). Optional lookup config
-- lets DeepSQL resolve a tenant id to a human-readable customer name by
-- running a cheap SELECT against the target DB.
CREATE TABLE IF NOT EXISTS connection_analytics_config (
    connection_id            VARCHAR(36)  PRIMARY KEY,
    tenant_column            VARCHAR(128),
    customer_lookup_table    VARCHAR(256),
    customer_lookup_id_col   VARCHAR(128),
    customer_lookup_name_col VARCHAR(256),
    daily_analysis_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ── customer dimension ────────────────────────────────────────────────────
-- One row per distinct tenant value seen on a connection. `customer_name` is
-- resolved lazily via the target-DB lookup and cached here so we do not
-- re-query the customer's database every analysis cycle.
CREATE TABLE IF NOT EXISTS slow_query_customer (
    id                VARCHAR(36)  PRIMARY KEY,
    connection_id     VARCHAR(36)  NOT NULL,
    customer_id       VARCHAR(128) NOT NULL,
    customer_name     VARCHAR(512),
    tenant_column     VARCHAR(128),
    first_seen_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name_resolved_at  TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_slow_query_customer UNIQUE (connection_id, customer_id)
);

CREATE INDEX IF NOT EXISTS idx_slow_query_customer_conn
    ON slow_query_customer (connection_id, last_seen_at DESC);

-- ── per-query × per-day fact (the time series) ────────────────────────────
-- pg_stat_statements / performance_schema report counters cumulative since
-- the last reset. Each daily run stores BOTH the raw cumulative value and
-- the delta vs the previous run. `counter_reset` flags the case where the
-- source stats were reset between runs (current < previous).
CREATE TABLE IF NOT EXISTS slow_query_run (
    id                        VARCHAR(36)      PRIMARY KEY,
    connection_id             VARCHAR(36)      NOT NULL,
    analysis_run_id           VARCHAR(36),
    fingerprint               VARCHAR(64)      NOT NULL,
    analyzed_on               DATE             NOT NULL,
    captured_at               TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    calls_cumulative          BIGINT,
    total_exec_ms_cumulative  DOUBLE PRECISION,
    calls_delta               BIGINT,
    total_exec_ms_delta       DOUBLE PRECISION,
    mean_exec_ms              DOUBLE PRECISION,
    max_exec_ms               DOUBLE PRECISION,
    p95_exec_ms               DOUBLE PRECISION,
    rows_examined_delta       BIGINT,
    rows_sent_delta           BIGINT,
    prev_run_id               VARCHAR(36),
    regression_factor         DOUBLE PRECISION,
    counter_reset             BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_slow_query_run UNIQUE (connection_id, fingerprint, analyzed_on)
);

CREATE INDEX IF NOT EXISTS idx_slow_query_run_timeline
    ON slow_query_run (connection_id, fingerprint, analyzed_on DESC);
CREATE INDEX IF NOT EXISTS idx_slow_query_run_regression
    ON slow_query_run (connection_id, analyzed_on DESC, regression_factor DESC);

-- ── literal-bearing executions captured from the slow query log ───────────
-- pg_stat_statements strips literals, so per-customer attribution can only
-- come from sources that keep them. The slow-log ingestion pipeline writes
-- one row here per slow execution, with the tenant id extracted from the
-- WHERE clause.
CREATE TABLE IF NOT EXISTS slow_query_sample (
    id                VARCHAR(36)      PRIMARY KEY,
    connection_id     VARCHAR(36)      NOT NULL,
    fingerprint       VARCHAR(64)      NOT NULL,
    customer_id       VARCHAR(128),
    raw_sql           TEXT,
    exec_ms           DOUBLE PRECISION,
    rows_examined     BIGINT,
    rows_sent         BIGINT,
    captured_at       TIMESTAMP        NOT NULL,
    source            VARCHAR(32)      NOT NULL DEFAULT 'SLOW_LOG',
    ingested_at       TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_slow_query_sample_query
    ON slow_query_sample (connection_id, fingerprint, captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_slow_query_sample_customer
    ON slow_query_sample (connection_id, customer_id, captured_at DESC);

-- ── per-query × per-customer × per-day rollup ─────────────────────────────
-- Derived from slow_query_sample. This is what answers "did query X regress
-- for customer Y over the last 30 days".
CREATE TABLE IF NOT EXISTS slow_query_customer_day (
    id                VARCHAR(36)      PRIMARY KEY,
    connection_id     VARCHAR(36)      NOT NULL,
    fingerprint       VARCHAR(64)      NOT NULL,
    customer_id       VARCHAR(128)     NOT NULL,
    day               DATE             NOT NULL,
    sample_count      BIGINT           NOT NULL DEFAULT 0,
    mean_exec_ms      DOUBLE PRECISION,
    max_exec_ms       DOUBLE PRECISION,
    total_exec_ms     DOUBLE PRECISION,
    prev_day_mean_ms  DOUBLE PRECISION,
    regression_factor DOUBLE PRECISION,
    created_at        TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_slow_query_customer_day UNIQUE (connection_id, fingerprint, customer_id, day)
);

CREATE INDEX IF NOT EXISTS idx_slow_query_customer_day_timeline
    ON slow_query_customer_day (connection_id, fingerprint, customer_id, day DESC);
