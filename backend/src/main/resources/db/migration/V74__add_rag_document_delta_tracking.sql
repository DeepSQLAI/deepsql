ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS last_seen_run_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_rag_docs_connection_type_seen
    ON rag_documents (connection_id, type, last_seen_run_id);
