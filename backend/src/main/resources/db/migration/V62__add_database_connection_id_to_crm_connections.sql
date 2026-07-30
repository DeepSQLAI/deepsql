-- Associate CRM connections with database connections
-- so the chat system can serve ticket data when users ask about tickets
-- while chatting with a specific database connection.

ALTER TABLE crm_connections
    ADD COLUMN database_connection_id VARCHAR(36);

ALTER TABLE crm_connections
    ADD CONSTRAINT fk_crm_connections_database_connection
    FOREIGN KEY (database_connection_id)
    REFERENCES encrypted_credentials(id)
    ON DELETE SET NULL;

CREATE INDEX idx_crm_connections_database_connection_id
    ON crm_connections(database_connection_id);
