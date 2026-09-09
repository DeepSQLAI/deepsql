/**
 * Permission and role constants — mirrors the backend Permission and Role enums.
 *
 * The backend is the authority: `/auth/me` and `/permissions/me` return the user's
 * effective permission codes, and the UI gates on those. These constants exist so
 * components refer to a permission by name instead of a string literal.
 */
export const PERMISSIONS = {
  // Section / menu permissions — one per top-level sidebar destination.
  VIEW_AGENT: 'VIEW_AGENT',
  VIEW_DASHBOARDS: 'VIEW_DASHBOARDS',
  VIEW_DIGEST: 'VIEW_DIGEST',
  VIEW_BRAIN: 'VIEW_BRAIN',
  VIEW_PERFORMANCE: 'VIEW_PERFORMANCE',
  VIEW_EDITOR: 'VIEW_EDITOR',

  // Read permissions
  VIEW_DASHBOARD: 'VIEW_DASHBOARD',
  VIEW_SCHEMA: 'VIEW_SCHEMA',
  VIEW_SLOW_QUERIES: 'VIEW_SLOW_QUERIES',
  VIEW_GROWTH: 'VIEW_GROWTH',
  VIEW_PLAYBOOKS: 'VIEW_PLAYBOOKS',

  // Core product permissions
  EXECUTE_QUERIES: 'EXECUTE_QUERIES',
  USE_CHAT: 'USE_CHAT',
  EXPORT_DATA: 'EXPORT_DATA',

  // Action permissions
  RUN_ANALYSIS: 'RUN_ANALYSIS',
  RUN_INGESTION: 'RUN_INGESTION',
  EXECUTE_PLAYBOOKS: 'EXECUTE_PLAYBOOKS',
  USE_INDEX_ADVISOR: 'USE_INDEX_ADVISOR',
  MANAGE_ALERTS: 'MANAGE_ALERTS',

  // Workspaces
  MANAGE_DASHBOARD_WORKSPACES: 'MANAGE_DASHBOARD_WORKSPACES',

  // Administrative permissions
  MANAGE_CONNECTIONS: 'MANAGE_CONNECTIONS',
  MANAGE_SETTINGS: 'MANAGE_SETTINGS',
  MANAGE_USERS: 'MANAGE_USERS',
  MANAGE_INVITE_CODES: 'MANAGE_INVITE_CODES',
  MANAGE_PERMISSIONS: 'MANAGE_PERMISSIONS',
}

/**
 * Built-in role codes. A user's role may also be a custom role code, which will not
 * appear here — never treat "not in ROLES" as "invalid role".
 */
export const ROLES = {
  ADMIN: 'ADMIN',
  DBA: 'DBA',
  DATA_ENGINEER: 'DATA_ENGINEER',
  DEVELOPER: 'DEVELOPER',
}

/** Display labels for the built-in roles. */
export const ROLE_LABELS = {
  [ROLES.ADMIN]: 'Admin',
  [ROLES.DBA]: 'DBA',
  [ROLES.DATA_ENGINEER]: 'Data Engineer',
  [ROLES.DEVELOPER]: 'Developer',
}

/**
 * The permissions each built-in role holds by default — mirrors the backend's
 * Permission.defaultRoles. Used only by the legacy `minRole` guard prop to ask "does this
 * user have everything role X would have"; the authoritative permission set always comes
 * from the backend, never from this table.
 */
export const ROLE_BASELINE_PERMISSIONS = {
  [ROLES.ADMIN]: Object.values(PERMISSIONS),
  [ROLES.DBA]: [
    PERMISSIONS.VIEW_AGENT, PERMISSIONS.VIEW_DASHBOARDS, PERMISSIONS.VIEW_DIGEST,
    PERMISSIONS.VIEW_BRAIN, PERMISSIONS.VIEW_PERFORMANCE, PERMISSIONS.VIEW_EDITOR,
    PERMISSIONS.MANAGE_CONNECTIONS, PERMISSIONS.MANAGE_SETTINGS,
  ],
  [ROLES.DATA_ENGINEER]: [
    PERMISSIONS.VIEW_AGENT, PERMISSIONS.VIEW_DASHBOARDS, PERMISSIONS.VIEW_EDITOR,
  ],
  [ROLES.DEVELOPER]: [
    PERMISSIONS.VIEW_AGENT, PERMISSIONS.VIEW_DIGEST, PERMISSIONS.VIEW_DASHBOARDS,
    PERMISSIONS.VIEW_PERFORMANCE, PERMISSIONS.VIEW_EDITOR,
  ],
}

/** Legacy role names from the two-role model still stored on old user rows. */
const ROLE_ALIASES = {
  EDITOR: ROLES.DEVELOPER,
  VIEWER: ROLES.DEVELOPER,
  USER: ROLES.DEVELOPER,
}

/**
 * Normalise a role value to its code.
 *
 * <p>Unlike the old version this does NOT collapse unknown values to DEVELOPER: a custom
 * role code is a perfectly valid role, and mapping it onto a built-in one would show the
 * wrong menus. Returns the uppercased code as-is for anything unrecognised.
 */
export function normalizeRole(role) {
  if (!role) return null
  const upper = String(role).trim().toUpperCase()
  if (!upper) return null
  return ROLE_ALIASES[upper] || upper
}

export function isBuiltInRole(role) {
  const normalized = normalizeRole(role)
  return Boolean(normalized && Object.values(ROLES).includes(normalized))
}

/** Human label for a role code, falling back to a readable form of a custom code. */
export function roleLabel(role, fallbackName = null) {
  const normalized = normalizeRole(role)
  if (!normalized) return 'Unknown'
  if (ROLE_LABELS[normalized]) return ROLE_LABELS[normalized]
  if (fallbackName) return fallbackName
  // ANALYST_TEAM -> "Analyst Team"
  return normalized
    .toLowerCase()
    .split('_')
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

export function isAdminRole(role) {
  return normalizeRole(role) === ROLES.ADMIN
}

/**
 * Whether a permission set grants a permission. Admin is a fixed point on the backend
 * (it holds every permission), so no special case is needed here.
 */
export function hasPermissionIn(permissions, permission) {
  if (!permission) return false
  if (permissions instanceof Set) return permissions.has(permission)
  return Array.isArray(permissions) && permissions.includes(permission)
}
