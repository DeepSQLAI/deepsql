import { PERMISSIONS, isAdminRole } from './permissions'

export const AGENTS_ENABLED = false

/**
 * Sections whose content belongs to a connection: a non-admin additionally needs
 * content access on the selected connection, not just the section permission.
 */
const CONNECTION_CONTENT_SECTIONS = new Set([
  ...(AGENTS_ENABLED ? ['brain'] : []),
  'company-knowledge',
  'dashboards',
])

/**
 * The permission that opens each sidebar section.
 *
 * <p>This replaced a minimum-role table. The roles no longer form a hierarchy — Data
 * Engineer has Dashboards but not Digest, Developer has Digest but no connection
 * settings — so "role is at least X" cannot express who sees what. Each section now
 * names the permission that opens it, and the backend decides who holds it (including
 * for custom roles and admin overrides).
 */
const SECTION_PERMISSION = {
  agent: PERMISSIONS.VIEW_AGENT,
  'agent-chat': PERMISSIONS.VIEW_AGENT,
  digest: PERMISSIONS.VIEW_DIGEST,
  ...(AGENTS_ENABLED ? { brain: PERMISSIONS.VIEW_BRAIN } : {}),
  'company-knowledge': PERMISSIONS.VIEW_BRAIN,
  dashboards: PERMISSIONS.VIEW_DASHBOARDS,
  performance: PERMISSIONS.VIEW_PERFORMANCE,
  editor: PERMISSIONS.VIEW_EDITOR,
}

export function getSectionPermission(section) {
  return SECTION_PERMISSION[resolveSectionAlias(section)] || null
}

function resolveSectionAlias(section) {
  switch (section) {
    case 'agent':
      // Legacy chat section id — Agent chat is now the first sidebar tab.
      return 'agent-chat'
    case 'monitor':
    case 'schema':
      // The old "Brain" (schema) section has been folded into Company Knowledge
      // (now rebranded as the "Brain" surface). Persisted nav state from older
      // sessions still routes correctly.
      return 'company-knowledge'
    case 'brain':
      return AGENTS_ENABLED ? 'brain' : 'company-knowledge'
    case 'schema-docs':
      // Schema Docs was folded into Company Knowledge → Schema Context tab.
      return 'company-knowledge'
    case 'docs':
      // The Docs sidebar tab was removed.
      return 'agent-chat'
    case 'slow-queries':
    case 'workload-analysis':
      // Slow Queries and Workload Analysis were merged into Performance.
      return 'performance'
    default:
      return section
  }
}

export function hasConnectionContentAccess(connection) {
  return Boolean(connection?.canManageContent)
}

/**
 * Whether the user may open a section.
 *
 * @param section       sidebar section id
 * @param role          the user's role code (used only for the admin bypass)
 * @param connection    the selected connection, for content-scoped sections
 * @param permissions   the user's effective permission codes (Set or Array)
 */
export function canAccessHomeSection(section, role, connection = null, permissions = null) {
  const normalizedSection = resolveSectionAlias(section)
  const requiredPermission = SECTION_PERMISSION[normalizedSection]
  if (!requiredPermission) {
    return false
  }

  if (isAdminRole(role)) {
    return true
  }

  // With no permission set loaded yet, deny rather than guess. The nav re-renders
  // as soon as /auth/me resolves, and showing a section the user cannot use is
  // worse than showing it a moment late.
  const granted = permissions instanceof Set
    ? permissions.has(requiredPermission)
    : Array.isArray(permissions) && permissions.includes(requiredPermission)
  if (!granted) {
    return false
  }

  if (CONNECTION_CONTENT_SECTIONS.has(normalizedSection)) {
    return hasConnectionContentAccess(connection)
  }

  return true
}

const SECTION_ORDER = [
  'agent-chat',
  'dashboards',
  'digest',
  ...(AGENTS_ENABLED ? ['brain'] : []),
  'company-knowledge',
  'performance',
  'editor',
]

export function getDefaultHomeSection(role, connection = null, permissions = null) {
  const firstVisible = SECTION_ORDER.find((section) =>
    canAccessHomeSection(section, role, connection, permissions))
  return firstVisible || 'agent-chat'
}

export function normalizeHomeSection(section, role, connection = null, permissions = null) {
  const normalizedSection = resolveSectionAlias(section)

  if (!SECTION_PERMISSION[normalizedSection]) {
    return getDefaultHomeSection(role, connection, permissions)
  }

  return canAccessHomeSection(normalizedSection, role, connection, permissions)
    ? normalizedSection
    : getDefaultHomeSection(role, connection, permissions)
}

export function getConnectionAccessLabel(connection) {
  if (!connection) return null
  if (connection.ownershipType === 'OWNED') {
    return 'Owned by me'
  }
  if (connection.ownershipType === 'ASSIGNED') {
    return 'Assigned by admin'
  }
  if (connection.ownershipType === 'ADMIN') {
    return 'Admin access'
  }
  return null
}

/**
 * Badge shown next to a connection name.
 *
 * <p>Only ownership is worth surfacing. The old "Full Access" / "Chat + Editor" labels
 * described an access-level split that no longer exists — every assigned user now gets
 * full content access — so they were noise that implied a choice the product no longer
 * offers. Returning null falls back to the connection's dbType, which is more useful.
 */
export function getConnectionAccessBadge(connection) {
  if (!connection) return null
  if (connection.accessLevel === 'OWNER') {
    return 'Owner'
  }
  if (connection.accessLevel === 'ADMIN') {
    return 'Admin'
  }
  return null
}
