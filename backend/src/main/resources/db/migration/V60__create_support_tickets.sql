-- Generic support ticket table (multi-source)
CREATE TABLE support_tickets (
    id                BIGSERIAL PRIMARY KEY,
    source            VARCHAR(30) NOT NULL,        -- HUBSPOT, ZENDESK, etc.
    external_id       VARCHAR(100) NOT NULL,        -- source-specific ticket ID
    connection_id     VARCHAR(36) NOT NULL,          -- references crm_connections(id)
    subject           TEXT,
    content           TEXT,
    pipeline          VARCHAR(255),
    pipeline_stage    VARCHAR(255),
    priority          VARCHAR(50),
    status            VARCHAR(100),
    created_at_ext    TIMESTAMPTZ,                  -- source createdate (TZ-aware)
    modified_at_ext   TIMESTAMPTZ,                  -- source last modified (TZ-aware)
    raw_properties    JSONB,                        -- full source properties blob
    synced_at         TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_source_connection_external UNIQUE (source, connection_id, external_id)
);

CREATE INDEX idx_support_tickets_source ON support_tickets (source);
CREATE INDEX idx_support_tickets_modified ON support_tickets (modified_at_ext);
CREATE INDEX idx_support_tickets_source_modified ON support_tickets (source, modified_at_ext);
CREATE INDEX idx_support_tickets_connection ON support_tickets (connection_id);
CREATE INDEX idx_support_tickets_priority ON support_tickets (priority);
CREATE INDEX idx_support_tickets_status ON support_tickets (status);

-- Per-source per-connection sync checkpoint (opaque, connector-owned)
CREATE TABLE ticket_sync_state (
    source            VARCHAR(30) NOT NULL,           -- HUBSPOT, ZENDESK, etc.
    connection_id     VARCHAR(36) NOT NULL,            -- references crm_connections(id)
    checkpoint        TEXT,                            -- opaque connector-owned checkpoint token
    last_sync_at      TIMESTAMPTZ,
    records_synced    INTEGER DEFAULT 0,
    error_message     TEXT,
    status            VARCHAR(20) DEFAULT 'IDLE',
    PRIMARY KEY (source, connection_id)
);
