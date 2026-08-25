-- Dashboard workspaces + custom roles.
--
-- NOTE: this repository has no Flyway runtime (see CLAUDE.md). Schema is managed by
-- spring.jpa.hibernate.ddl-auto=update, which creates these tables and the new
-- saved_dashboards.workspace_id column from the JPA entities on startup. This file is
-- the hand-maintained changelog: apply it with psql only if you need the schema without
-- letting Hibernate touch the database.

-- ── Dashboard workspaces ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS dashboard_workspaces (
    id            UUID PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(500),
    color         VARCHAR(32),
    created_by    VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dashboard_workspaces_connection
    ON dashboard_workspaces (connection_id);

-- One workspace name per connection, so the folder chips stay unambiguous.
CREATE UNIQUE INDEX IF NOT EXISTS ux_dashboard_workspaces_conn_name
    ON dashboard_workspaces (connection_id, LOWER(name));

-- ── Membership ───────────────────────────────────────────────────────────────
-- Keyed by username to match connection_access_grant, so an impersonated ("View as")
-- session resolves membership as the target user with no extra lookup.
CREATE TABLE IF NOT EXISTS dashboard_workspace_members (
    id             UUID PRIMARY KEY,
    workspace_id   UUID NOT NULL REFERENCES dashboard_workspaces (id) ON DELETE CASCADE,
    username       VARCHAR(255) NOT NULL,
    workspace_role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    added_by       VARCHAR(255),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dashboard_ws_members_workspace
    ON dashboard_workspace_members (workspace_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_ws_members_username
    ON dashboard_workspace_members (username);
CREATE UNIQUE INDEX IF NOT EXISTS ux_dashboard_ws_member
    ON dashboard_workspace_members (workspace_id, LOWER(username));

-- ── Dashboards join a workspace ──────────────────────────────────────────────
-- Nullable on purpose: NULL means "not grouped", governed purely by the connection ACL
-- exactly as every dashboard was before workspaces existed. Deliberately NOT a cascading
-- FK — deleting a workspace detaches its dashboards rather than destroying them
-- (DashboardWorkspaceService.deleteWorkspace).
ALTER TABLE saved_dashboards
    ADD COLUMN IF NOT EXISTS workspace_id UUID;

CREATE INDEX IF NOT EXISTS idx_saved_dashboards_workspace_id
    ON saved_dashboards (workspace_id);

-- ── Custom roles ─────────────────────────────────────────────────────────────
-- code shares a namespace with the built-in Role names because both are written to
-- users.role; CustomRoleService refuses a code that collides with a built-in one.
CREATE TABLE IF NOT EXISTS custom_roles (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(64) NOT NULL,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(500),
    permission_codes TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_custom_roles_code ON custom_roles (UPPER(code));

-- ── role_permission_overrides widens from the old Role/Permission enums ──────
-- The column is already a string; this only relaxes any length limit so a custom role
-- code fits. Existing rows ('ADMIN', 'DEVELOPER') keep working untouched.
ALTER TABLE role_permission_overrides
    ALTER COLUMN role TYPE VARCHAR(64);

-- Hibernate generated CHECK constraints from the ORIGINAL two-role / 20-permission
-- enums and ddl-auto=update never drops a stale constraint. Left in place they reject
-- every new value: inserting ('DBA','VIEW_AGENT') fails with
--   violates check constraint "role_permission_overrides_permission_code_check"
-- (observed on a live install, not inferred). Dropping them is what lets an override be
-- recorded for DBA/DATA_ENGINEER or a custom role. The application enum is the
-- authority for these values; SchemaConstraintRefreshInitializer applies this at boot.
ALTER TABLE role_permission_overrides
    DROP CONSTRAINT IF EXISTS role_permission_overrides_role_check;
ALTER TABLE role_permission_overrides
    DROP CONSTRAINT IF EXISTS role_permission_overrides_permission_code_check;
