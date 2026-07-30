'use client'

import React, { useMemo, useState, useEffect, useCallback, useRef, Component } from 'react'
import ForceGraph2D from 'react-force-graph-2d'
import { RefreshCcw, Loader, Database, Link2, Filter, ChevronDown, Maximize, Minimize } from 'lucide-react'
import { schemaAPI, brainAPI } from '@/lib/api/client'
import { useTableClassifications } from '@/lib/hooks/queries/useBrain'
import styles from './SchemaERD3D.module.css'

export const DEFAULT_ROLE_FILTERS = {
    FACT: true,
    DIMENSION: true,
    BRIDGE: true,
    LOOKUP: true,
    ORPHANED: true,
    AGGREGATE: true
}

const ROLE_OPTIONS = [
    { role: 'FACT', label: 'Fact', color: '#6b9bd1' },
    { role: 'DIMENSION', label: 'Dimension', color: '#7fb3a3' },
    { role: 'BRIDGE', label: 'Bridge', color: '#a78fb8' },
    { role: 'LOOKUP', label: 'Lookup', color: '#d4a574' },
    { role: 'ORPHANED', label: 'Orphaned', color: '#c97a7a' },
    { role: 'AGGREGATE', label: 'Aggregate', color: '#7bb3c4' }
]

// Catches errors in the schema diagram so the rest of the Brain tab (Understanding, Key Columns, etc.) still renders
export class ERD3DErrorBoundary extends Component {
    state = { hasError: false, error: null }
    static getDerivedStateFromError() { return { hasError: true } }
    componentDidCatch(err) {
        console.warn('Schema ER diagram failed:', err)
        this.setState({ error: err })
    }
    render() {
        if (this.state.hasError) {
            const message = this.state.error?.message || this.state.error?.toString() || 'Unknown error'
            return (
                <div className={styles.wrapper}>
                    <div className={styles.header}>
                        <h3>Schema ER Diagram</h3>
                        <p>Scroll to zoom, drag to pan. Hover over a table or relationship for details; click to pin in the panel below.</p>
                    </div>
                    <div className={styles.placeholder}>
                        <span>Schema diagram could not be loaded for this connection.</span>
                        <span className={styles.errorDetail} title={message}>{message}</span>
                    </div>
                </div>
            )
        }
        return this.props.children
    }
}

function formatRowCount(n) {
    if (n == null || n < 0) return '—'
    if (n >= 1e9) return (n / 1e9).toFixed(1) + 'B'
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M'
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K'
    return String(n)
}

function escapeHtml(s) {
    if (s == null) return ''
    const str = String(s)
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/**
 * Table role filter dropdown for Schema Diagram header.
 * Overview-style: transparent trigger, white panel, grey text/icons. Icon-only trigger.
 */
export function SchemaDiagramFilter({ roleFilters, setRoleFilters }) {
    const [open, setOpen] = useState(false)
    const ref = useRef(null)

    useEffect(() => {
        const handleClickOutside = (e) => {
            if (ref.current && !ref.current.contains(e.target)) setOpen(false)
        }
        if (open) {
            document.addEventListener('mousedown', handleClickOutside)
            return () => document.removeEventListener('mousedown', handleClickOutside)
        }
    }, [open])

    return (
        <div className={styles.filterDropdownWrap} ref={ref}>
            <button
                type="button"
                className={styles.filterTriggerIconOnly}
                onClick={() => setOpen(!open)}
                aria-expanded={open}
                aria-haspopup="true"
                title="Filter by table role"
            >
                <Filter size={16} />
            </button>
            {open && (
                <div className={styles.filterDropdownMenuOverview}>
                    {ROLE_OPTIONS.map(({ role, label, color }) => (
                        <label key={role} className={styles.filterOptionOverview}>
                            <input
                                type="checkbox"
                                checked={roleFilters[role] !== false}
                                onChange={(e) => {
                                    setRoleFilters(prev => ({ ...prev, [role]: e.target.checked }))
                                }}
                            />
                            <span className={styles.filterColor} style={{ backgroundColor: color }} />
                            <span>{label}</span>
                        </label>
                    ))}
                </div>
            )}
        </div>
    )
}

/**
 * Schema ER Diagram visualization (2D).
 * Uses the same API data as the former 3D version; 2D is reliable across browsers.
 * Scroll to zoom, drag to pan. Click a table or link to see details.
 */
export function SchemaERD3D({
    connectionId,
    height = 700,
    hideTitle = false,
    roleFilters: roleFiltersProp,
    setRoleFilters: setRoleFiltersProp
}) {
    const fgRef = useRef(null)
    const [data, setData] = useState(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState(null)
    // Removed selectedNode/selectedLink - no click events, only hover
    const [keyColumnsByTable, setKeyColumnsByTable] = useState({})
    const [understanding, setUnderstanding] = useState(null)
    const [syncing, setSyncing] = useState(false)
    const [syncResult, setSyncResult] = useState(null)
    const [schemaChanges, setSchemaChanges] = useState([])
    const [internalRoleFilters, setInternalRoleFilters] = useState(DEFAULT_ROLE_FILTERS)
    const roleFilters = roleFiltersProp ?? internalRoleFilters
    const setRoleFilters = setRoleFiltersProp ?? setInternalRoleFilters
    const [filterDropdownOpen, setFilterDropdownOpen] = useState(false)
    const filterDropdownRef = useRef(null)
    const filterRenderedInHeader = roleFiltersProp != null
    const [isFullScreen, setIsFullScreen] = useState(false)
    const [windowHeight, setWindowHeight] = useState(typeof window !== 'undefined' ? window.innerHeight : 800)

    // Toggle full screen
    const toggleFullScreen = useCallback(() => {
        setIsFullScreen(prev => !prev)
    }, [])

    // Update window height on resize
    useEffect(() => {
        const handleResize = () => setWindowHeight(window.innerHeight)
        window.addEventListener('resize', handleResize)
        return () => window.removeEventListener('resize', handleResize)
    }, [])

    // Close full screen on Escape key
    useEffect(() => {
        const handleEsc = (e) => {
            if (e.key === 'Escape' && isFullScreen) setIsFullScreen(false)
        }
        window.addEventListener('keydown', handleEsc)
        return () => window.removeEventListener('keydown', handleEsc)
    }, [isFullScreen])

    const parseRecentSchemaChanges = useCallback((changes) => {
        const cutoff = Date.now() - (24 * 60 * 60 * 1000)
        const newTables = new Set()
        const newRelationshipsBySignature = new Set()
        const newRelationshipsByPair = new Set()

        if (!Array.isArray(changes)) {
            return { newTables, newRelationshipsBySignature, newRelationshipsByPair }
        }

        changes.forEach((change) => {
            if (!change?.detectedAt) return
            const detectedAt = new Date(change.detectedAt).getTime()
            if (!Number.isFinite(detectedAt) || detectedAt < cutoff) return

            if (change.changeType === 'TABLE_ADDED') {
                const tableName = (change.objectName || change.tableName || '').toLowerCase()
                if (tableName) newTables.add(tableName)
                return
            }

            if (change.changeType === 'FOREIGN_KEY_ADDED') {
                const sourceTable = (change.tableName || '').toLowerCase()
                const fk = change.changeDetails?.foreignKey || {}
                const targetTable = (fk.referencedTable || '').toLowerCase()
                const fromKey = (fk.column || '').toLowerCase()
                const toKey = (fk.referencedColumn || '').toLowerCase()
                if (sourceTable && targetTable) {
                    newRelationshipsByPair.add(`${sourceTable}|${targetTable}`)
                    if (fromKey || toKey) {
                        newRelationshipsBySignature.add(`${sourceTable}|${targetTable}|${fromKey}|${toKey}`)
                    }
                }
            }
        })

        return { newTables, newRelationshipsBySignature, newRelationshipsByPair }
    }, [])

    // Close filter dropdown when clicking outside
    useEffect(() => {
        const handleClickOutside = (e) => {
            if (filterDropdownRef.current && !filterDropdownRef.current.contains(e.target)) {
                setFilterDropdownOpen(false)
            }
        }
        if (filterDropdownOpen) {
            document.addEventListener('mousedown', handleClickOutside)
            return () => document.removeEventListener('mousedown', handleClickOutside)
        }
    }, [filterDropdownOpen])

    // Fetch table classifications
    const { data: tableClassifications } = useTableClassifications(connectionId)

    const loadVisualization = useCallback(() => {
        if (!connectionId) {
            setData(null)
            setLoading(false)
            return
        }
        setLoading(true)
        setError(null)
        schemaAPI
            .getVisualization(connectionId)
            .then((res) => {
                if (!res?.success || !res?.erDiagram) {
                    setData(null)
                    setError(res?.message || 'No ER diagram data')
                    return
                }
                const er = res.erDiagram
                const entities = er.entities || []
                const relationships = er.relationships || []

                console.log('[ER Diagram] Loaded visualization:', {
                    entities: entities.length,
                    relationships: relationships.length,
                    relationshipDetails: relationships.map(r => ({
                        from: r.from,
                        to: r.to,
                        type: r.type,
                        fk: r.foreignKey
                    }))
                })
                
                const nodes = entities.map((e, i) => {
                    const pos = e.position
                    return {
                        id: e.name,
                        name: e.name,
                        type: e.type || 'table',
                        tableRole: null, // Will be set by useEffect when classifications load
                        rowCount: e.rowCount != null ? Number(e.rowCount) : null,
                        sizeBytes: e.sizeBytes != null ? Number(e.sizeBytes) : null,
                        description: e.description || null,
                        ...(pos != null && typeof pos.x === 'number' && typeof pos.y === 'number'
                            ? { x: pos.x, y: pos.y }
                            : {}),
                        columns: (e.columns || []).map((c) => ({
                            name: c.name,
                            type: c.type || '',
                            primaryKey: c.primaryKey,
                            nullable: c.nullable
                        }))
                    }
                })

                const nodeIds = new Set(nodes.map((n) => n.id))
                // Case-insensitive resolve: if API returns different casing, still match to node id
                const idByLower = Object.fromEntries(nodes.map((n) => [n.id.toLowerCase(), n.id]))
                const resolveId = (name) => {
                    if (!name) return null
                    if (nodeIds.has(name)) return name
                    return idByLower[name.toLowerCase()] ?? null
                }
                const linkSet = new Set()
                const links = relationships
                    .map((r) => {
                        const fromId = resolveId(r.from)
                        const toId = resolveId(r.to)
                        if (!fromId || !toId) return null
                        const key = `${fromId}\0${toId}\0${r.foreignKey || ''}`
                        if (linkSet.has(key)) return null
                        linkSet.add(key)
                        return {
                            source: fromId,
                            target: toId,
                            label: r.foreignKey || r.type || '',
                            fromKey: r.fromKey ?? r.foreignKey ?? '',
                            toKey: r.toKey ?? ''
                        }
                    })
                    .filter(Boolean)

                console.log('[ER Diagram] Processed graph data:', {
                    nodes: nodes.length,
                    links: links.length,
                    linkDetails: links.map(l => `${l.source} -> ${l.target} (${l.label})`)
                })

                setData({ nodes, links })
            })
            .catch((err) => {
                setError(err?.message || 'Failed to load schema visualization')
                setData(null)
                console.error('[ER Diagram] Load error:', err)
            })
            .finally(() => {
                setLoading(false)
            })
    }, [connectionId])

    useEffect(() => {
        loadVisualization()
    }, [loadVisualization])

    useEffect(() => {
        if (!connectionId) {
            setSchemaChanges([])
            return
        }
        let cancelled = false
        schemaAPI.getSchemaChanges(connectionId)
            .then((changes) => {
                if (!cancelled) setSchemaChanges(Array.isArray(changes) ? changes : [])
            })
            .catch(() => {
                if (!cancelled) setSchemaChanges([])
            })
        return () => { cancelled = true }
    }, [connectionId])

    // Merge table classifications into nodes when classifications are loaded
    useEffect(() => {
        if (!data?.nodes || !tableClassifications) return
        
        const tableRoleMap = {}
        if (Array.isArray(tableClassifications)) {
            tableClassifications.forEach(tc => {
                if (tc.tableName && tc.tableRole) {
                    tableRoleMap[tc.tableName.toLowerCase()] = tc.tableRole
                }
            })
        }
        
        // Only update if classifications have changed
        const hasChanges = data.nodes.some(n => {
            const expectedRole = tableRoleMap[(n.name || '').toLowerCase()] || null
            return n.tableRole !== expectedRole
        })
        
        if (hasChanges) {
            setData(prevData => {
                if (!prevData?.nodes) return prevData
                return {
                    ...prevData,
                    nodes: prevData.nodes.map(n => ({
                        ...n,
                        tableRole: tableRoleMap[(n.name || '').toLowerCase()] || null
                    }))
                }
            })
        }
    }, [tableClassifications, data])

    // Load key columns and understanding for detail panel (Brain description, key columns)
    useEffect(() => {
        if (!connectionId) return
        let cancelled = false
        Promise.all([
            brainAPI.getKeyColumns(connectionId).catch(() => ({ topColumns: [] })),
            brainAPI.getUnderstanding(connectionId).catch(() => null)
        ]).then(([keyColRes, understandingRes]) => {
            if (cancelled) return
            const keyCols = keyColRes?.topColumns || []
            const byTable = {}
            if (Array.isArray(keyCols)) {
                keyCols.forEach((kc) => {
                    const t = (kc.tableName || '').toLowerCase()
                    if (!byTable[t]) byTable[t] = []
                    byTable[t].push(kc)
                })
            }
            setKeyColumnsByTable(byTable)
            setUnderstanding(understandingRes || null)
        })
        return () => { cancelled = true }
    }, [connectionId])

    const handleSync = useCallback(async () => {
        if (!connectionId || syncing) return
        setSyncing(true)
        setError(null)
        setSyncResult(null)
        try {
            console.log('[ER Diagram] Starting sync for connection:', connectionId)
            const result = await brainAPI.analyzeInferredRelationships(connectionId)
            console.log('[ER Diagram] Sync result:', {
                totalRelationships: result.totalRelationshipsInferred,
                queriesAnalyzed: result.totalQueriesAnalyzed,
                fromSlowLogs: result.queriesFromSlowLogs,
                fromLineage: result.queriesFromLineage,
                parseFailures: result.parseFailures,
                message: result.message
            })
            setSyncResult(result)
            await loadVisualization()
            console.log('[ER Diagram] Reloaded visualization and relationships')
        } catch (err) {
            const errorMsg = err?.message || 'Failed to sync inferred relationships'
            setError(errorMsg)
            console.error('[ER Diagram] Sync error:', err)
        } finally {
            setSyncing(false)
        }
    }, [connectionId, syncing, loadVisualization])

    const recentSchemaMap = useMemo(() => parseRecentSchemaChanges(schemaChanges), [schemaChanges, parseRecentSchemaChanges])

    const graphData = useMemo(() => {
        if (!data?.nodes?.length) return { nodes: [], links: [] }
        // Filter nodes based on role filters
        const filteredNodes = data.nodes
            .filter((n) => {
                const role = n.tableRole || 'UNKNOWN'
                return roleFilters[role] !== false // Show if explicitly true or undefined (for UNKNOWN)
            })
            .map((n) => ({
                ...n,
                isNewInLast24h: recentSchemaMap.newTables.has((n.name || '').toLowerCase())
            }))
        const nodeById = Object.fromEntries(filteredNodes.map((n) => [n.id, n]))
        // Filter links to only include those between visible nodes
        const links = (data.links || [])
            .map((l) => ({
                ...l,
                isNewInLast24h: (() => {
                    const source = (l.source || '').toString().toLowerCase()
                    const target = (l.target || '').toString().toLowerCase()
                    const fromKey = (l.fromKey || '').toLowerCase()
                    const toKey = (l.toKey || '').toLowerCase()
                    const signature = `${source}|${target}|${fromKey}|${toKey}`
                    const pair = `${source}|${target}`
                    return recentSchemaMap.newRelationshipsBySignature.has(signature) ||
                        recentSchemaMap.newRelationshipsByPair.has(pair)
                })(),
                source: nodeById[l.source] ?? l.source,
                target: nodeById[l.target] ?? l.target
            }))
            .filter((l) => l.source && l.target && typeof l.source === 'object' && typeof l.target === 'object')
        return { nodes: filteredNodes, links }
    }, [data, roleFilters, recentSchemaMap])

    // Removed click handlers - only hover, drag, zoom interactions

    const nodeColor = useCallback((node) => {
        if (!node) return '#94a3b8'
        return '#111827' // Black
    }, [])

    const linkColor = useCallback((link) => {
        return '#E5E7EB' // Very light grey for links
    }, [])

    // Custom node rendering for "Full Form" on zoom - REMOVED
    const nodeCanvasObject = undefined;

    const linkWidth = useCallback((link) => {
        return 1 // Uniform link width
    }, [])

    const getKeyColumnNames = useCallback((node) => {
        if (!node?.name) return []
        const list = keyColumnsByTable[(node.name || '').toLowerCase()]
        return Array.isArray(list) ? list.map((kc) => (kc.columnName || '').toLowerCase()) : []
    }, [keyColumnsByTable])

    const nodeLabel = useCallback((node) => {
        if (!node) return ''
        const keyCols = getKeyColumnNames(node)
        const name = escapeHtml(node.name || '')
        const typeTag = node.type ? ` <span style="color:#6b7280;font-size:11px;text-transform:lowercase">${escapeHtml(node.type)}</span>` : ''
        const roleTag = node.tableRole ? ` <span style="background:#374151;color:#D1D5DB;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:600;margin-left:6px;text-transform:uppercase">${escapeHtml(node.tableRole)}</span>` : ''
        const rows = formatRowCount(node.rowCount)
        const cols = (node.columns || []).slice(0, 12)
        const more = (node.columns || []).length > 12 ? (node.columns.length - 12) : 0
        const colItems = cols.map((c) => {
            const colNameLower = (c.name || '').toLowerCase()
            const isInferred = keyCols.includes(colNameLower) && !c.primaryKey
            const isKey = c.primaryKey
            const badges = []
            if (isKey) badges.push('<span style="background:#6b9bd1;color:#fff;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:500;margin-left:4px">Key</span>')
            if (isInferred) badges.push('<span style="background:#94a3b8;color:#fff;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:500;margin-left:4px">Inferred key</span>')
            if (c.nullable === false) badges.push('<span style="color:#6b7280;font-size:10px;margin-left:4px">NOT NULL</span>')
            const typeStr = c.type ? ` <span style="color:#6b7280;font-size:11px">${escapeHtml(c.type)}</span>` : ''
            return `<li style="margin:3px 0;font-size:12px;list-style:disc;margin-left:14px;color:#374151">${escapeHtml(c.name)}${typeStr} ${badges.join(' ')}</li>`
        }).join('')
        const moreLine = more > 0 ? `<li style="margin:2px 0;font-size:11px;color:#4b5563;margin-left:14px">… and ${more} more</li>` : ''
        return `<div class="erd-node-tooltip-inner" style="font-family:system-ui,sans-serif;font-size:12px;max-width:300px;padding:12px 14px;background:#ffffff;border:1px solid #E5E7EB;border-radius:10px;box-shadow:0 4px 16px rgba(0,0,0,0.08);color:#111827">
<div style="font-weight:600;color:#111827;font-size:13px;margin-bottom:6px">Table: ${name}${typeTag}${roleTag}</div>
<div style="color:#4b5563;margin-top:4px;font-size:12px">Rows: ${rows}</div>
<div style="font-weight:600;margin-top:10px;color:#111827;font-size:12px">Columns</div>
<ul style="margin:6px 0 0 0;padding-left:16px;color:#374151">${colItems}${moreLine}</ul>
</div>`
    }, [getKeyColumnNames])

    // Custom node pointer area for hit detection - REMOVED
    const nodePointerAreaPaint = undefined;

    const linkLabel = useCallback((link) => {
        if (!link) return ''
        const src = link.source?.name ?? link.source ?? ''
        const tgt = link.target?.name ?? link.target ?? ''
        const fromK = link.fromKey != null && link.fromKey !== '' ? `.${escapeHtml(link.fromKey)}` : ''
        const toK = link.toKey != null && link.toKey !== '' ? `.${escapeHtml(link.toKey)}` : ''
        const fallback = link.label ? ` (${escapeHtml(link.label)})` : ''
        return `<div class="erd-link-tooltip-inner" style="font-family:system-ui,sans-serif;font-size:12px;padding:12px 14px;background:#ffffff;border:1px solid #E5E7EB;border-radius:10px;box-shadow:0 4px 16px rgba(0,0,0,0.08);color:#111827">
<div style="font-weight:600;color:#111827;font-size:13px;margin-bottom:6px">Relationship</div>
<div style="margin-top:6px;color:#374151;font-size:12px"><span style="font-weight:500;color:#111827">${escapeHtml(src)}${fromK}</span> <span style="color:#6b7280">→</span> <span style="font-weight:500;color:#111827">${escapeHtml(tgt)}${toK}</span>${(fromK || toK) ? '' : fallback}</div>
</div>`
    }, [])

    const getTableInsights = useCallback((node) => {
        if (!node?.name || !understanding?.tables) return null
        const t = understanding.tables.find(
            (x) => (x.tableName || '').toLowerCase() === (node.name || '').toLowerCase()
        )
        const reasons = t?.scoreReasons
        if (!Array.isArray(reasons) || reasons.length === 0) return null
        return reasons.map((r) => (r && r.label) ? r.label : String(r))
    }, [understanding])

    // Tune force simulation when we have graph data (must run before any early return to satisfy Rules of Hooks)
    useEffect(() => {
        if (!graphData?.nodes?.length || !fgRef.current) return
        const fg = fgRef.current
        try {
            // Stronger repulsion to separate nodes significantly
            const charge = fg.d3Force('charge')
            if (charge && typeof charge.strength === 'function') {
                charge.strength(-800)
            }
            // Longer links to give ample space for expanded cards
            const link = fg.d3Force('link')
            if (link && typeof link.distance === 'function') {
                link.distance(200)
            }
        } catch (_) {}
    }, [graphData?.nodes?.length])

    if (!connectionId) return null

    if (loading && !syncing) {
        return (
            <div className={styles.wrapper}>
                {!hideTitle && (
                    <div className={styles.header}>
                        <div className={styles.headerRow}>
                            <div>
                                <h3>Schema ER Diagram</h3>
                                <p>Hover over tables or relationships for details. Drag to pan, scroll to zoom.</p>
                            </div>
                            <button
                                className={styles.syncButton}
                                onClick={handleSync}
                                disabled={syncing || !connectionId}
                                title="Sync relationships from slow query logs"
                            >
                                <RefreshCcw className={styles.syncIcon} size={16} />
                                <span>Sync</span>
                            </button>
                        </div>
                    </div>
                )}
                <div className={styles.placeholder}>
                    <span>Loading schema…</span>
                </div>
            </div>
        )
    }

    if (error || !data?.nodes?.length) {
        return (
            <div className={styles.wrapper}>
                {!hideTitle && (
                    <div className={styles.header}>
                        <div className={styles.headerRow}>
                            <div>
                                <h3>Schema ER Diagram</h3>
                                <p>Hover over tables or relationships for details. Drag to pan, scroll to zoom.</p>
                            </div>
                            <button
                                className={styles.syncButton}
                                onClick={handleSync}
                                disabled={syncing || !connectionId}
                                title="Sync relationships from slow query logs"
                            >
                                {syncing ? (
                                    <>
                                        <Loader className={styles.syncIcon} size={16} />
                                        <span>Syncing...</span>
                                    </>
                                ) : (
                                    <>
                                        <RefreshCcw className={styles.syncIcon} size={16} />
                                        <span>Sync</span>
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                )}
                <div className={styles.placeholder}>
                    <span>{error || 'No schema data available for visualization'}</span>
                </div>
            </div>
        )
    }

    return (
        <div className={`${styles.wrapper} ${isFullScreen ? styles.fullScreen : ''}`}>
            {!hideTitle && (
                <div className={styles.header}>
                    <div className={styles.headerRow}>
                        <div>
                            <h3>Schema ER Diagram</h3>
                            <p>Scroll to zoom, drag to pan. Hover over a table or relationship for details; click to pin in the panel below.</p>
                        </div>
                        <button
                            className={styles.syncButton}
                            onClick={handleSync}
                            disabled={syncing || !connectionId}
                            title="Sync relationships from slow query logs"
                        >
                            {syncing ? (
                                <>
                                    <Loader className={styles.syncIcon} size={16} />
                                    <span>Syncing...</span>
                                </>
                            ) : (
                                <>
                                    <RefreshCcw className={styles.syncIcon} size={16} />
                                    <span>Sync</span>
                                </>
                            )}
                        </button>
                        <button 
                            className={styles.fullScreenToggle}
                            onClick={toggleFullScreen}
                            title={isFullScreen ? "Exit Full Screen" : "Full Screen"}
                        >
                            {isFullScreen ? <Minimize size={16} /> : <Maximize size={16} />}
                        </button>
                    </div>
                </div>
            )}
            {/* Filter by Table Role - only when not rendered in parent header */}
            {!filterRenderedInHeader && (
                <div className={styles.filterDropdownWrap} ref={filterDropdownRef}>
                    <button
                        type="button"
                        className={styles.filterDropdownTrigger}
                        onClick={() => setFilterDropdownOpen(!filterDropdownOpen)}
                        aria-expanded={filterDropdownOpen}
                        aria-haspopup="true"
                    >
                        <Filter size={14} />
                        <span>Filter by Table Role</span>
                        <ChevronDown size={14} className={filterDropdownOpen ? styles.filterChevronOpen : ''} />
                    </button>
                    {filterDropdownOpen && (
                        <div className={styles.filterDropdownMenu}>
                            {ROLE_OPTIONS.map(({ role, label, color }) => (
                                <label key={role} className={styles.filterOption}>
                                    <input
                                        type="checkbox"
                                        checked={roleFilters[role] !== false}
                                        onChange={(e) => {
                                            setRoleFilters(prev => ({ ...prev, [role]: e.target.checked }))
                                        }}
                                    />
                                    <span className={styles.filterColor} style={{ backgroundColor: color }} />
                                    <span>{label}</span>
                                </label>
                            ))}
                        </div>
                    )}
                </div>
            )}
            <div className={styles.graphContainer} style={{ width: '100%', minHeight: height }}>
                <ForceGraph2D
                    ref={fgRef}
                    graphData={{ nodes: graphData.nodes || [], links: graphData.links || [] }}
                    height={isFullScreen ? windowHeight - 100 : height}
                    nodeLabel={nodeLabel}
                    linkLabel={linkLabel}
                    nodeColor={nodeColor}
                    nodeVal={(n) => {
                        const rowCount = n.rowCount
                        if (rowCount == null || rowCount <= 0) return 6
                        const MIN_R = 4
                        const MAX_R = 28
                        const capped = Math.min(rowCount, 1e9)
                        const logScale = Math.log10(1 + capped) / Math.log10(1 + 1e9)
                        return Math.min(MAX_R, Math.max(MIN_R, MIN_R + (MAX_R - MIN_R) * logScale))
                    }}
                    linkColor={linkColor}
                    linkWidth={linkWidth}
                    linkDirectionalArrowLength={3.5}
                    linkDirectionalArrowRelPos={1}
                    linkCurvature={0.25}
                    enableNodeDrag={true}
                    enableZoomInteraction={true}
                    enablePanInteraction={true}
                    warmupTicks={120}
                    cooldownTicks={0}
                    cooldownTime={0}
                    d3AlphaDecay={0.028}
                    d3VelocityDecay={0.7}
                />
            </div>
            {/* Sync Result Summary */}
            {syncResult && (
                <div className={styles.syncResult}>
                    <div className={styles.syncResultHeader}>
                        <Database size={16} />
                        <span>
                            Sync completed: {syncResult.totalRelationshipsInferred || 0} relationships found
                            {syncResult.totalQueriesAnalyzed > 0 && (
                                <> from {syncResult.totalQueriesAnalyzed} queries</>
                            )}
                        </span>
                    </div>
                    {syncResult.message && (
                        <div className={styles.syncResultDetails}>
                            {syncResult.queriesFromSlowLogs > 0 && (
                                <span>Slow logs: {syncResult.queriesFromSlowLogs} queries</span>
                            )}
                            {syncResult.queriesFromLineage > 0 && (
                                <span>Lineage: {syncResult.queriesFromLineage} queries</span>
                            )}
                            {syncResult.parseFailures > 0 && (
                                <span className={styles.warning}>Parse failures: {syncResult.parseFailures}</span>
                            )}
                        </div>
                    )}
                </div>
            )}
        </div>
    )
}
