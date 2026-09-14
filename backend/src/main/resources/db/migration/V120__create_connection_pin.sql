-- Per-user default connection ("pin").
--
-- One row per user: the unique constraint is what makes a pinned connection *the*
-- default rather than one of several. Pinning a second connection moves this row
-- (ConnectionPinService.pin) instead of inserting another.
--
-- Deliberately not a column on database_connection: a connection can be shared with
-- several users via connection_access_grant, and one user's default must not decide
-- what anyone else opens on.
--
-- NOTE: this repository has no Flyway runtime — schema is applied by
-- spring.jpa.hibernate.ddl-auto=update from the ConnectionPin entity. Apply by hand
-- with psql only if you manage schema manually.
CREATE TABLE connection_pin (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_connection_pin_username ON connection_pin (username);

CREATE INDEX idx_connection_pin_connection ON connection_pin (connection_id);
