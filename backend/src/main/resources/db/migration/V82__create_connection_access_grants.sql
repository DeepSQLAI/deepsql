CREATE TABLE connection_access_grant (
    id BIGSERIAL PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    username VARCHAR(255) NOT NULL,
    access_level VARCHAR(32) NOT NULL,
    granted_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_connection_access_grant_connection_user
    ON connection_access_grant (connection_id, username);

CREATE INDEX idx_connection_access_grant_username
    ON connection_access_grant (username);

CREATE INDEX idx_connection_access_grant_connection
    ON connection_access_grant (connection_id);
