-- V99: installs_telemetry — single-row table holding the install's anonymous
-- identifiers for usage telemetry.
--
-- install_id     anonymous UUID sent in every event envelope; survives docker
--                volume preservation across upgrades. A --purge-data install
--                generates a new one.
-- install_secret 32 random bytes used locally to derive user_hash =
--                sha256(internal_user_id || install_secret)[:16]. NEVER sent.
-- install_token  random per-install bearer token sent in Authorization header
--                to telemetry.deepsql.ai (used in Phase 2; stored now so the
--                relay TOFU registration just works when it lands).
--
-- The CHECK constraint enforces single-row: id = 1 always.

CREATE TABLE installs_telemetry (
    id              INTEGER     PRIMARY KEY DEFAULT 1,
    install_id      UUID        NOT NULL UNIQUE,
    install_secret  BYTEA       NOT NULL,
    install_token   VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT installs_telemetry_singleton CHECK (id = 1)
);

-- No INSERT here — InstallTelemetryBootstrap generates on first app start.
