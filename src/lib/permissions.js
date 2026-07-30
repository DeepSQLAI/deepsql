/**
 * Permission constants - mirrors backend Permission enum.
 *
 * These are the permission codes returned by the backend.
 * DO NOT use these directly in components - use ACTIONS instead.
 */
export const PERMISSIONS = {
  // Admin-only product area permissions
  VIEW_DASHBOARD: 'VIEW_DASHBOARD',
  VIEW_SCHEMA: 'VIEW_SCHEMA',
  VIEW_SLOW_QUERIES: 'VIEW_SLOW_QUERIES',
  VIEW_BRAIN: 'VIEW_BRAIN',
  VIEW_PERFORMANCE: 'VIEW_PERFORMANCE',
  VIEW_GROWTH: 'VIEW_GROWTH',
  VIEW_PLAYBOOKS: 'VIEW_PLAYBOOKS',

  // Developer permissions
  EXECUTE_QUERIES: 'EXECUTE_QUERIES',
  USE_CHAT: 'USE_CHAT',
  EXPORT_DATA: 'EXPORT_DATA',

  // Admin-only action permissions
  RUN_ANALYSIS: 'RUN_ANALYSIS',
  RUN_INGESTION: 'RUN_INGESTION',
  EXECUTE_PLAYBOOKS: 'EXECUTE_PLAYBOOKS',
  USE_INDEX_ADVISOR: 'USE_INDEX_ADVISOR',
  MANAGE_ALERTS: 'MANAGE_ALERTS',

  // Admin permissions (ADMIN only)
  MANAGE_CONNECTIONS: 'MANAGE_CONNECTIONS',
  MANAGE_USERS: 'MANAGE_USERS',
  MANAGE_INVITE_CODES: 'MANAGE_INVITE_CODES',
  MANAGE_SETTINGS: 'MANAGE_SETTINGS',
  MANAGE_PERMISSIONS: 'MANAGE_PERMISSIONS',
}

/**
 * Role constants - mirrors backend Role enum.
 */
export const ROLES = {
  DEVELOPER: 'DEVELOPER',
  ADMIN: 'ADMIN',
}

const ROLE_ALIASES = {
  ADMIN: ROLES.ADMIN,
  DEVELOPER: ROLES.DEVELOPER,
  EDITOR: ROLES.DEVELOPER,
  VIEWER: ROLES.DEVELOPER,
  USER: ROLES.DEVELOPER,
}

export function normalizeRole(role) {
  if (!role) return null
  return ROLE_ALIASES[String(role).trim().toUpperCase()] || null
}

/**
 * Role hierarchy level - higher number = more permissions.
 */
export const ROLE_LEVELS = {
  [ROLES.DEVELOPER]: 0,
  [ROLES.ADMIN]: 1,
}

/**
 * Check if a role is at or above another role in the hierarchy.
 */
export function roleIsAtLeast(userRole, minRole) {
  const normalizedUserRole = normalizeRole(userRole)
  const normalizedMinRole = normalizeRole(minRole)

  if (!normalizedUserRole || !normalizedMinRole) {
    return false
  }

  return (ROLE_LEVELS[normalizedUserRole] ?? -1) >= (ROLE_LEVELS[normalizedMinRole] ?? Number.MAX_SAFE_INTEGER)
}
