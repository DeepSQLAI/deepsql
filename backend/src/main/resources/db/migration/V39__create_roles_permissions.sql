-- V39: Create roles and permissions system for RBAC

-- Update users table to add role column
-- Default to 'EDITOR' for new signups, existing users get 'EDITOR'
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) DEFAULT 'EDITOR';

-- Update existing users who have 'USER' role to 'EDITOR'
UPDATE users SET role = 'EDITOR' WHERE role = 'USER' OR role IS NULL;

-- Update admin user to have ADMIN role
UPDATE users SET role = 'ADMIN' WHERE username = 'admin';

-- Create role_permissions table for reference (static mapping)
-- This table documents the permissions for each role but actual permission
-- checking happens in code via the Role enum
CREATE TABLE IF NOT EXISTS role_permissions (
    id BIGSERIAL PRIMARY KEY,
    role VARCHAR(20) NOT NULL,
    permission VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(role, permission)
);

-- Insert default role permissions mapping
-- VIEWER: Read-only access
INSERT INTO role_permissions (role, permission) VALUES
    ('VIEWER', 'VIEW_DASHBOARD'),
    ('VIEWER', 'VIEW_SCHEMA'),
    ('VIEWER', 'VIEW_SLOW_QUERIES'),
    ('VIEWER', 'VIEW_BRAIN')
ON CONFLICT (role, permission) DO NOTHING;

-- EDITOR: Standard user permissions (includes VIEWER permissions)
INSERT INTO role_permissions (role, permission) VALUES
    ('EDITOR', 'VIEW_DASHBOARD'),
    ('EDITOR', 'VIEW_SCHEMA'),
    ('EDITOR', 'VIEW_SLOW_QUERIES'),
    ('EDITOR', 'VIEW_BRAIN'),
    ('EDITOR', 'EXECUTE_QUERIES'),
    ('EDITOR', 'USE_CHAT'),
    ('EDITOR', 'RUN_ANALYSIS'),
    ('EDITOR', 'EXECUTE_PLAYBOOKS'),
    ('EDITOR', 'USE_INDEX_ADVISOR')
ON CONFLICT (role, permission) DO NOTHING;

-- ADMIN: Full access (includes EDITOR permissions)
INSERT INTO role_permissions (role, permission) VALUES
    ('ADMIN', 'VIEW_DASHBOARD'),
    ('ADMIN', 'VIEW_SCHEMA'),
    ('ADMIN', 'VIEW_SLOW_QUERIES'),
    ('ADMIN', 'VIEW_BRAIN'),
    ('ADMIN', 'EXECUTE_QUERIES'),
    ('ADMIN', 'USE_CHAT'),
    ('ADMIN', 'RUN_ANALYSIS'),
    ('ADMIN', 'EXECUTE_PLAYBOOKS'),
    ('ADMIN', 'USE_INDEX_ADVISOR'),
    ('ADMIN', 'MANAGE_CONNECTIONS'),
    ('ADMIN', 'MANAGE_USERS'),
    ('ADMIN', 'MANAGE_INVITE_CODES'),
    ('ADMIN', 'MANAGE_SETTINGS')
ON CONFLICT (role, permission) DO NOTHING;

-- Create index for faster lookups
CREATE INDEX IF NOT EXISTS idx_role_permissions_role ON role_permissions(role);
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);
