-- Code-driven Company Knowledge enrichment: sources, scan jobs, and suggestion review queue.

-- Allow CODE_DERIVED as a SchemaDocumentation source. The original CHECK constraint
-- only listed USER/AI_GENERATED/CSV_IMPORT, and Hibernate's ddl-auto=update doesn't
-- update CHECK constraints when an enum gets a new value.
ALTER TABLE schema_documentation DROP CONSTRAINT IF EXISTS schema_documentation_source_check;
ALTER TABLE schema_documentation ADD CONSTRAINT schema_documentation_source_check
  CHECK (source::text = ANY (ARRAY['USER','AI_GENERATED','CSV_IMPORT','CODE_DERIVED']::text[]));

CREATE TABLE IF NOT EXISTS code_scan_source (
    id              VARCHAR(36)   PRIMARY KEY,
    connection_id   VARCHAR(64)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    kind            VARCHAR(20)   NOT NULL,                -- UPLOAD (v1), GIT (v2)
    archive_sha256  VARCHAR(64),
    total_bytes     BIGINT,
    file_count      INTEGER,
    schedule_cron   VARCHAR(40),                            -- null = manual only
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(120),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_scanned_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_code_scan_source_connection
    ON code_scan_source(connection_id, active);

CREATE TABLE IF NOT EXISTS code_scan_job (
    id                   VARCHAR(36)  PRIMARY KEY,
    source_id            VARCHAR(36)  NOT NULL REFERENCES code_scan_source(id) ON DELETE CASCADE,
    connection_id        VARCHAR(64)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,              -- PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
    progress             INTEGER      NOT NULL DEFAULT 0,    -- 0..100
    current_step         VARCHAR(120),
    files_total          INTEGER      NOT NULL DEFAULT 0,
    files_parsed         INTEGER      NOT NULL DEFAULT 0,
    chunks_sent          INTEGER      NOT NULL DEFAULT 0,
    suggestions_emitted  INTEGER      NOT NULL DEFAULT 0,
    started_at           TIMESTAMP,
    completed_at         TIMESTAMP,
    message              TEXT,
    triggered_by         VARCHAR(120),
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_code_scan_job_source
    ON code_scan_job(source_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_code_scan_job_connection_status
    ON code_scan_job(connection_id, status);

CREATE TABLE IF NOT EXISTS code_knowledge_suggestion (
    id              VARCHAR(36)  PRIMARY KEY,
    job_id          VARCHAR(36)  NOT NULL REFERENCES code_scan_job(id) ON DELETE CASCADE,
    connection_id   VARCHAR(64)  NOT NULL,
    target_kind     VARCHAR(20)  NOT NULL,                  -- SCHEMA_DOC | KNOWLEDGE_ENTRY
    target_object   VARCHAR(255),                            -- table or table.column FQN
    title           VARCHAR(200) NOT NULL,
    content         TEXT         NOT NULL,
    payload         JSONB,                                   -- extra fields (alternates, businessTerms, entryType)
    linked_tables   JSONB,
    linked_columns  JSONB,
    source_files    JSONB,                                   -- array of {path, startLine, endLine, snippet}
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/REJECTED/SUPERSEDED
    decision_note   TEXT,
    decided_by      VARCHAR(120),
    decided_at      TIMESTAMP,
    applied_doc_id  VARCHAR(36),                             -- back-ref to created SchemaDocumentation
    applied_entry_id VARCHAR(36),                            -- back-ref to created CompanyKnowledgeEntry
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_code_suggestion_connection_status
    ON code_knowledge_suggestion(connection_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_code_suggestion_job
    ON code_knowledge_suggestion(job_id);
CREATE INDEX IF NOT EXISTS idx_code_suggestion_target
    ON code_knowledge_suggestion(connection_id, target_kind, target_object);
