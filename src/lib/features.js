import { ROLES, roleIsAtLeast } from './permissions'

export const AGENTS_ENABLED = false

const CONNECTION_CONTENT_SECTIONS = new Set([
  ...(AGENTS_ENABLED ? ['brain'] : []),
  'company-knowledge',
  'dashboards',
])

const BASE_SECTION_MIN_ROLE = {
  agent: ROLES.DEVELOPER,
  'agent-chat': ROLES.DEVELOPER,
  digest: ROLES.ADMIN,
  ...(AGENTS_ENABLED ? { brain: ROLES.DEVELOPER } : {}),
  'company-knowledge': ROLES.DEVELOPER,
  dashboards: ROLES.DEVELOPER,
  'slow-queries': ROLES.ADMIN,
  'workload-analysis': ROLES.ADMIN,
  editor: ROLES.DEVELOPER,
  docs: ROLES.DEVELOPER,
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
      // Persisted nav state from older sessions still routes correctly.
      return 'company-knowledge'
    default:
      return section
  }
}

export function hasConnectionContentAccess(connection) {
  return Boolean(connection?.canManageContent)
}

export function canAccessHomeSection(section, role, connection = null) {
  const normalizedSection = resolveSectionAlias(section)
  const minRole = BASE_SECTION_MIN_ROLE[normalizedSection]

  if (!minRole || !roleIsAtLeast(role, minRole)) {
    return false
  }

  if (roleIsAtLeast(role, ROLES.ADMIN)) {
    return true
  }

  if (CONNECTION_CONTENT_SECTIONS.has(normalizedSection)) {
    return hasConnectionContentAccess(connection)
  }

  return true
}

export function getDefaultHomeSection(role, connection = null) {
  // Prefer the first sidebar tab (Agent). Fall through in sidebar order when
  // the user can't access a section.
  const orderedSections = [
    'agent-chat',
    'dashboards',
    'digest',
    ...(AGENTS_ENABLED ? ['brain'] : []),
    'company-knowledge',
    'slow-queries',
    'workload-analysis',
    'editor',
    'docs',
  ]
  const firstVisible = orderedSections.find((section) => canAccessHomeSection(section, role, connection))
  return firstVisible || 'docs'
}

export function normalizeHomeSection(section, role, connection = null) {
  const normalizedSection = resolveSectionAlias(section)

  if (!BASE_SECTION_MIN_ROLE[normalizedSection]) {
    return getDefaultHomeSection(role, connection)
  }

  return canAccessHomeSection(normalizedSection, role, connection)
    ? normalizedSection
    : getDefaultHomeSection(role, connection)
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

export function getConnectionAccessBadge(connection) {
  if (!connection) return null
  if (connection.accessLevel === 'OWNER') {
    return 'Owner'
  }
  if (connection.accessLevel === 'ADMIN') {
    return 'Admin'
  }
  if (connection.accessLevel === 'FULL_CONTENT') {
    return 'Full Access'
  }
  if (connection.accessLevel === 'CHAT_EDITOR') {
    return 'Chat + Editor'
  }
  return null
}
