-- Role Permission Overrides table
-- Stores exceptions to the default permission assignments
-- This table is SPARSE - only stores overrides, not all role-permission pairs

CREATE TABLE IF NOT EXISTS role_permission_overrides (
    id BIGSERIAL PRIMARY KEY,
    role VARCHAR(50) NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    granted BOOLEAN NOT NULL DEFAULT true,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(255),

    -- Ensure unique role-permission combination
    CONSTRAINT uk_role_permission UNIQUE (role, permission_code)
);

-- Index for fast lookups by role
CREATE INDEX idx_role_permission_overrides_role ON role_permission_overrides(role);

-- Add comments
COMMENT ON TABLE role_permission_overrides IS 'Custom overrides to default role-permission assignments';
COMMENT ON COLUMN role_permission_overrides.role IS 'Role enum value (VIEWER, EDITOR, ADMIN)';
COMMENT ON COLUMN role_permission_overrides.permission_code IS 'Permission enum value';
COMMENT ON COLUMN role_permission_overrides.granted IS 'true=grant, false=revoke';
COMMENT ON COLUMN role_permission_overrides.reason IS 'Optional reason for audit purposes';
