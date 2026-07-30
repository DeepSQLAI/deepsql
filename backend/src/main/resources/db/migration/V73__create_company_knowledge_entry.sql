CREATE TABLE IF NOT EXISTS company_knowledge_entry (
    id UUID PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    title VARCHAR(200) NOT NULL,
    entry_type VARCHAR(40) NOT NULL,
    content TEXT NOT NULL,
    linked_tables JSONB,
    linked_columns JSONB,
    created_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_company_knowledge_connection
    ON company_knowledge_entry(connection_id);

CREATE INDEX IF NOT EXISTS idx_company_knowledge_type
    ON company_knowledge_entry(connection_id, entry_type);
