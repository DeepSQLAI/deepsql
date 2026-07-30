-- V77: Simplify RBAC to ADMIN and DEVELOPER only

ALTER TABLE users ALTER COLUMN role SET DEFAULT 'DEVELOPER';

UPDATE users
SET role = CASE
    WHEN UPPER(COALESCE(role, '')) = 'ADMIN' THEN 'ADMIN'
    ELSE 'DEVELOPER'
END;

UPDATE users
SET role = 'ADMIN'
WHERE username = 'admin';

INSERT INTO role_permission_overrides (role, permission_code, granted, reason, created_at, updated_at, updated_by)
SELECT
    'DEVELOPER',
    legacy.permission_code,
    legacy.granted,
    legacy.reason,
    legacy.created_at,
    legacy.updated_at,
    legacy.updated_by
FROM (
    SELECT DISTINCT ON (permission_code)
        permission_code,
        granted,
        reason,
        created_at,
        updated_at,
        updated_by
    FROM role_permission_overrides
    WHERE role IN ('EDITOR', 'USER', 'VIEWER')
    ORDER BY permission_code,
        CASE role
            WHEN 'EDITOR' THEN 0
            WHEN 'USER' THEN 1
            ELSE 2
        END,
        updated_at DESC,
        id DESC
) legacy
WHERE NOT EXISTS (
    SELECT 1
    FROM role_permission_overrides existing
    WHERE existing.role = 'DEVELOPER'
      AND existing.permission_code = legacy.permission_code
);

DELETE FROM role_permission_overrides
WHERE role NOT IN ('ADMIN', 'DEVELOPER');

DELETE FROM role_permissions;

INSERT INTO role_permissions (role, permission) VALUES
    ('DEVELOPER', 'EXECUTE_QUERIES'),
    ('DEVELOPER', 'USE_CHAT'),
    ('DEVELOPER', 'EXPORT_DATA'),
    ('ADMIN', 'VIEW_DASHBOARD'),
    ('ADMIN', 'VIEW_SCHEMA'),
    ('ADMIN', 'VIEW_SLOW_QUERIES'),
    ('ADMIN', 'VIEW_BRAIN'),
    ('ADMIN', 'VIEW_PERFORMANCE'),
    ('ADMIN', 'VIEW_GROWTH'),
    ('ADMIN', 'VIEW_PLAYBOOKS'),
    ('ADMIN', 'EXECUTE_QUERIES'),
    ('ADMIN', 'USE_CHAT'),
    ('ADMIN', 'EXPORT_DATA'),
    ('ADMIN', 'RUN_ANALYSIS'),
    ('ADMIN', 'RUN_INGESTION'),
    ('ADMIN', 'EXECUTE_PLAYBOOKS'),
    ('ADMIN', 'USE_INDEX_ADVISOR'),
    ('ADMIN', 'MANAGE_ALERTS'),
    ('ADMIN', 'MANAGE_CONNECTIONS'),
    ('ADMIN', 'MANAGE_USERS'),
    ('ADMIN', 'MANAGE_INVITE_CODES'),
    ('ADMIN', 'MANAGE_SETTINGS'),
    ('ADMIN', 'MANAGE_PERMISSIONS');
