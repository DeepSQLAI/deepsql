/**
 * Shared helpers for multi-schema object naming in the UI.
 *
 * Display / SQL rule:
 * - Default schemas (`public`, `dbo`) stay bare for single-schema ergonomics.
 * - All other schemas render and insert as `schema.table`.
 * - React keys and expand ids always use {@link objectKey} so `crm.orders`
 *   and `sales.orders` never collide.
 */

const DEFAULT_SCHEMAS = new Set(['public', 'dbo'])

export function isDefaultSchema(schemaName) {
  if (!schemaName) return true
  return DEFAULT_SCHEMAS.has(String(schemaName).trim().toLowerCase())
}

export function stripIdentQuotes(value) {
  return String(value || '').trim().replace(/[`"\[\]]/g, '')
}

/**
 * Human / SQL label: bare for default schema, otherwise `schema.table`.
 */
export function canonicalTableReference(tableOrSchema, maybeName) {
  let schemaName = ''
  let tableName = ''
  if (typeof tableOrSchema === 'string' && maybeName !== undefined) {
    schemaName = stripIdentQuotes(tableOrSchema)
    tableName = stripIdentQuotes(maybeName)
  } else if (typeof tableOrSchema === 'string') {
    const parsed = parseQualifiedName(tableOrSchema)
    schemaName = parsed.schema
    tableName = parsed.name
  } else {
    const obj = tableOrSchema || {}
    tableName = stripIdentQuotes(obj.tableName || obj.name || '')
    schemaName = stripIdentQuotes(obj.schema || obj.schemaName || '')
  }
  if (!tableName) return ''
  if (isDefaultSchema(schemaName)) return tableName
  return `${schemaName}.${tableName}`
}

/**
 * Stable unique key for lists / expand state — always includes schema when known.
 * Falls back to bare name only when schema is missing.
 */
export function objectKey(obj) {
  if (!obj) return ''
  const name = stripIdentQuotes(obj.tableName || obj.name || obj.table || '')
  const schema = stripIdentQuotes(obj.schema || obj.schemaName || '')
  if (!name) return ''
  if (schema) return `${schema}.${name}`
  return name
}

/** Split `schema.table` (last dot) into parts. Bare names → schema ''. */
export function parseQualifiedName(value) {
  const raw = stripIdentQuotes(value)
  if (!raw) return { schema: '', name: '' }
  const dot = raw.lastIndexOf('.')
  if (dot <= 0) return { schema: '', name: raw }
  return { schema: raw.slice(0, dot), name: raw.slice(dot + 1) }
}

/**
 * True when the connection has objects in more than one user schema
 * (or any non-default schema).
 */
export function connectionHasMultipleSchemas(objects = []) {
  const schemas = new Set()
  for (const obj of objects) {
    const schema = stripIdentQuotes(obj?.schema || obj?.schemaName || '') || 'public'
    schemas.add(schema.toLowerCase())
    if (schemas.size > 1) return true
  }
  return [...schemas].some((s) => !isDefaultSchema(s))
}

/**
 * Group objects by schema for explorer trees. Default schema sorts first.
 */
export function groupBySchema(objects = []) {
  const groups = new Map()
  for (const obj of objects) {
    const schema = stripIdentQuotes(obj?.schema || obj?.schemaName || '') || 'public'
    if (!groups.has(schema)) groups.set(schema, [])
    groups.get(schema).push(obj)
  }
  return [...groups.entries()].sort(([a], [b]) => {
    if (isDefaultSchema(a) && !isDefaultSchema(b)) return -1
    if (!isDefaultSchema(a) && isDefaultSchema(b)) return 1
    return a.localeCompare(b)
  })
}

/** Quote an identifier if it needs it (reserved / mixed case / non-plain). */
export function quoteIdent(ident, dialect = 'postgres') {
  const name = stripIdentQuotes(ident)
  if (!name) return ''
  const plain = /^[a-z_][a-z0-9_]*$/i.test(name)
  if (plain) return name
  if (dialect === 'mysql') return `\`${name.replace(/`/g, '``')}\``
  return `"${name.replace(/"/g, '""')}"`
}

/** Build `schema.table` (quoted when needed) for FROM / INSERT / etc. */
export function qualifyForSql(tableOrObj, dialect = 'postgres') {
  const ref = typeof tableOrObj === 'string'
    ? parseQualifiedName(tableOrObj)
    : {
        schema: stripIdentQuotes(tableOrObj?.schema || tableOrObj?.schemaName || ''),
        name: stripIdentQuotes(tableOrObj?.tableName || tableOrObj?.name || ''),
      }
  if (!ref.name) return ''
  const tableSql = quoteIdent(ref.name, dialect)
  if (!ref.schema || isDefaultSchema(ref.schema)) return tableSql
  return `${quoteIdent(ref.schema, dialect)}.${tableSql}`
}

/** Path-safe table id for REST `/tables/{id}/…` (supports schema.table). */
export function encodeTablePathId(tableOrObj) {
  const key = typeof tableOrObj === 'string' ? stripIdentQuotes(tableOrObj) : objectKey(tableOrObj)
  return encodeURIComponent(key)
}
