CREATE TABLE crm_connections (
    id                    VARCHAR(36) PRIMARY KEY,
    connection_name       VARCHAR(255) NOT NULL,
    crm_type              VARCHAR(30) NOT NULL,
    external_account_id   VARCHAR(255),
    encrypted_credentials BYTEA NOT NULL,
    owner_username        VARCHAR(255) NOT NULL,
    created_at            TIMESTAMPTZ DEFAULT NOW(),
    last_used             TIMESTAMPTZ,
    active                BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_crm_connections_type ON crm_connections (crm_type);
CREATE INDEX idx_crm_connections_owner ON crm_connections (owner_username);
