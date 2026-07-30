-- Delta-aware code scanning + provenance for code-derived schema docs.

-- Per-file hash table so re-scans of the same source can skip unchanged files
-- entirely. Indexed by (source_id, relative_path); the orchestrator partitions
-- the next walk into "changed files" (new or different sha) and "unchanged"
-- (matching sha) and only sends changed files to the LLM extractor.
CREATE TABLE IF NOT EXISTS code_scan_file_hash (
    source_id        VARCHAR(36)  NOT NULL,
    relative_path    VARCHAR(500) NOT NULL,
    sha256           VARCHAR(64)  NOT NULL,
    last_job_id      VARCHAR(36),
    last_scanned_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_id, relative_path)
);

CREATE INDEX IF NOT EXISTS idx_code_scan_file_hash_source
    ON code_scan_file_hash(source_id, last_scanned_at DESC);

-- Persist source-file provenance on schema_documentation so an approved
-- CODE_DERIVED row remembers which file/lines produced it. Mirrors the
-- shape stored on code_knowledge_suggestion.source_files
-- (List<{path, startLine, endLine, rationale}>).
ALTER TABLE schema_documentation
    ADD COLUMN IF NOT EXISTS source_files JSONB;
