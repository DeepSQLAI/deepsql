'use client'

import { useState, useEffect, useMemo } from 'react'
import { AlertCircle, Play, Loader, ChevronDown, ChevronRight, AlertTriangle, Info, LayoutGrid, List } from 'lucide-react'
import { useKeyColumns } from './hooks/useKeyColumns'
import { ActionGuard } from '@/components/ActionGuard'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Key Columns Panel component
 * Shows important columns based on query usage patterns
 */
export function KeyColumnsPanel({ connectionId, hideCTAs = false }) {

    const {
        data,
        loading,
        analyzing,
        error,
        fetchKeyColumns,
        analyzeKeyColumns
    } = useKeyColumns(connectionId)

    const [expandedRows, setExpandedRows] = useState(new Set())
    const [expandedTables, setExpandedTables] = useState(new Set())
    const [viewMode, setViewMode] = useState('grouped') // 'grouped' or 'flat'
    const [filters, setFilters] = useState({
        tableName: '',
        antiPatternsOnly: false,
        slowQuerySourceOnly: false,
        limit: 50
    })

    useEffect(() => {
        if (connectionId) {
            fetchKeyColumns(filters)
        }
    }, [connectionId, fetchKeyColumns])

    const handleAnalyze = async () => {
        try {
            await analyzeKeyColumns()
        } catch (err) {
            console.error('Analysis failed:', err)
        }
    }

    const toggleRow = (columnKey) => {
        setExpandedRows(prev => {
            const newSet = new Set(prev)
            if (newSet.has(columnKey)) {
                newSet.delete(columnKey)
            } else {
                newSet.add(columnKey)
            }
            return newSet
        })
    }

    const toggleTable = (tableName) => {
        setExpandedTables(prev => {
            const newSet = new Set(prev)
            if (newSet.has(tableName)) {
                newSet.delete(tableName)
            } else {
                newSet.add(tableName)
            }
            return newSet
        })
    }

    const getSeverityClass = (severity) => {
        const severityMap = {
            CRITICAL: styles.severityCritical,
            HIGH: styles.severityHigh,
            MEDIUM: styles.severityMedium,
            LOW: styles.severityLow
        }
        return severityMap[severity] || styles.severityMedium
    }

    const allColumns = data?.topColumns || []
    const antiPatternsDetected = data?.antiPatternsDetected || 0
    const totalColumnsAnalyzed = data?.totalColumnsAnalyzed || 0
    const metadata = data?.metadata || {}

    // Apply client-side filters
    const topColumns = allColumns.filter(column => {
        // Filter by slow query source (columns with slow query usage)
        if (filters.slowQuerySourceOnly) {
            const slowQueryUsage = column.usageBreakdown?.slowQueryUsage || 0
            if (slowQueryUsage === 0) return false
        }
        return true
    })

    // Group columns by table name (case-insensitive), sorted by number of columns descending
    const groupedByTable = useMemo(() => {
        const groups = {}
        const tableNameMap = {} // Maps lowercase to display name

        topColumns.forEach(column => {
            const rawTableName = column.tableName || 'Unknown'
            const tableKey = rawTableName.toLowerCase()

            // Keep track of preferred display name (prefer UPPER_CASE version if available)
            if (!tableNameMap[tableKey] || rawTableName === rawTableName.toUpperCase()) {
                tableNameMap[tableKey] = rawTableName
            }

            if (!groups[tableKey]) {
                groups[tableKey] = {
                    tableKey,
                    columns: [],
                    totalUsage: 0,
                    slowQueryUsage: 0,
                    distinctQueriesCount: 0,
                    antiPatternCount: 0,
                    maxScore: 0
                }
            }
            groups[tableKey].columns.push(column)
            groups[tableKey].totalUsage += column.usageBreakdown?.totalUsage || 0
            groups[tableKey].slowQueryUsage += column.usageBreakdown?.slowQueryUsage || 0
            groups[tableKey].distinctQueriesCount += column.usageBreakdown?.distinctQueriesCount || 0
            groups[tableKey].antiPatternCount += column.antiPatterns?.length || 0
            const score = column.enhancedImportanceScore || column.importanceScore || 0
            if (score > groups[tableKey].maxScore) {
                groups[tableKey].maxScore = score
            }
        })

        // Convert to array, add display name, and sort by number of columns descending
        return Object.values(groups)
            .map(group => ({
                ...group,
                tableName: tableNameMap[group.tableKey]
            }))
            .sort((a, b) => {
                // Primary sort: number of columns
                if (b.columns.length !== a.columns.length) {
                    return b.columns.length - a.columns.length
                }
                // Secondary sort: total usage
                return b.totalUsage - a.totalUsage
            })
    }, [topColumns])

    // Auto-expand top 3 tables on initial load
    useEffect(() => {
        if (groupedByTable.length > 0 && expandedTables.size === 0) {
            const topTables = groupedByTable.slice(0, 3).map(g => g.tableName)
            setExpandedTables(new Set(topTables))
        }
    }, [groupedByTable])

    return (
        <div className={styles.brainPanel}>
            {!hideCTAs && (
                <div className={styles.brainHeader}>
                    <div>
                        <h3>Key Columns Analysis</h3>
                        <p>Important columns identified from query patterns (JOINs, WHERE, GROUP BY, ORDER BY)</p>
                    </div>
                    {!hideCTAs && (
                        <div className={styles.brainHeaderActions}>
                            <ActionGuard action="analyze-key-columns">
                                <button
                                    className={styles.profileButton}
                                    onClick={handleAnalyze}
                                    disabled={analyzing || !connectionId}
                                >
                                    {analyzing ? <Loader className={styles.spinner} size={14} /> : <Play size={14} />}
                                    {analyzing ? 'Analyzing...' : 'Analyze Now'}
                                </button>
                            </ActionGuard>
                        </div>
                    )}
                </div>
            )}

            {error && (
                <div className={styles.brainError}>
                    <AlertCircle size={14} />
                    <span>{error}</span>
                </div>
            )}

            {/* Summary Stats */}
            {data && (
                <div className={styles.profileSummaryRow}>
                    <span className={styles.profileSummaryLabel}>Columns Analyzed:</span>
                    <span className={styles.profileSummaryValue}>{totalColumnsAnalyzed}</span>
                    <span className={styles.profileSummaryMeta}>·</span>
                    <span className={styles.profileSummaryLabel}>From Slow Queries:</span>
                    <span className={styles.profileSummaryValue}>
                        {allColumns.filter(c => (c.usageBreakdown?.slowQueryUsage || 0) > 0).length}
                    </span>
                    <span className={styles.profileSummaryMeta}>·</span>
                    <span className={styles.profileSummaryLabel}>Anti-patterns:</span>
                    <span className={styles.profileSummaryValue}>{antiPatternsDetected}</span>
                    {metadata.queriesAnalyzed > 0 && (
                        <>
                            <span className={styles.profileSummaryMeta}>·</span>
                            <span className={styles.profileSummaryLabel}>Queries Analyzed:</span>
                            <span className={styles.profileSummaryValue}>{metadata.queriesAnalyzed}</span>
                        </>
                    )}
                    {metadata.lookbackDays && (
                        <>
                            <span className={styles.profileSummaryMeta}>·</span>
                            <span className={styles.profileSummaryMeta}>Lookback: {metadata.lookbackDays} days</span>
                        </>
                    )}
                    {filters.slowQuerySourceOnly && (
                        <>
                            <span className={styles.profileSummaryMeta}>·</span>
                            <span className={styles.profileSummaryMeta} style={{ color: 'var(--color-info)' }}>
                                Showing {topColumns.length} slow query columns
                            </span>
                        </>
                    )}
                </div>
            )}

            {/* Filters */}
            <div style={{ marginBottom: '16px', display: 'flex', gap: '12px', flexWrap: 'wrap', alignItems: 'center' }}>
                <input
                    type="text"
                    placeholder="Filter by table name..."
                    value={filters.tableName}
                    onChange={(e) => setFilters(prev => ({ ...prev, tableName: e.target.value }))}
                    style={{
                        padding: '8px 12px',
                        border: '1px solid var(--color-light-3)',
                        borderRadius: 'var(--radius-sm)',
                        fontSize: 'var(--font-size-sm)'
                    }}
                />
                <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: 'var(--font-size-sm)' }}>
                    <input
                        type="checkbox"
                        checked={filters.slowQuerySourceOnly}
                        onChange={(e) => setFilters(prev => ({ ...prev, slowQuerySourceOnly: e.target.checked }))}
                    />
                    Slow query sources only
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: 'var(--font-size-sm)' }}>
                    <input
                        type="checkbox"
                        checked={filters.antiPatternsOnly}
                        onChange={(e) => setFilters(prev => ({ ...prev, antiPatternsOnly: e.target.checked }))}
                    />
                    Anti-patterns only
                </label>
                <button
                    onClick={() => fetchKeyColumns(filters)}
                    className={styles.cancelButton}
                    disabled={loading}
                >
                    {loading ? <Loader size={14} className={styles.spinner} /> : 'Apply Filters'}
                </button>

                {/* View Mode Toggle */}
                <div style={{ marginLeft: 'auto', display: 'flex', gap: '4px', background: 'var(--color-light-2)', borderRadius: 'var(--radius-sm)', padding: '2px' }}>
                    <button
                        onClick={() => setViewMode('grouped')}
                        style={{
                            padding: '6px 10px',
                            border: 'none',
                            borderRadius: 'var(--radius-xs)',
                            background: viewMode === 'grouped' ? 'var(--color-white)' : 'transparent',
                            color: viewMode === 'grouped' ? 'var(--color-primary)' : 'var(--color-grey)',
                            cursor: 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px',
                            fontSize: 'var(--font-size-xs)',
                            fontWeight: viewMode === 'grouped' ? 600 : 400,
                            boxShadow: viewMode === 'grouped' ? '0 1px 2px rgba(0,0,0,0.1)' : 'none'
                        }}
                        title="Group by table"
                    >
                        <LayoutGrid size={14} />
                        By Table
                    </button>
                    <button
                        onClick={() => setViewMode('flat')}
                        style={{
                            padding: '6px 10px',
                            border: 'none',
                            borderRadius: 'var(--radius-xs)',
                            background: viewMode === 'flat' ? 'var(--color-white)' : 'transparent',
                            color: viewMode === 'flat' ? 'var(--color-primary)' : 'var(--color-grey)',
                            cursor: 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px',
                            fontSize: 'var(--font-size-xs)',
                            fontWeight: viewMode === 'flat' ? 600 : 400,
                            boxShadow: viewMode === 'flat' ? '0 1px 2px rgba(0,0,0,0.1)' : 'none'
                        }}
                        title="Flat list"
                    >
                        <List size={14} />
                        Flat
                    </button>
                </div>
            </div>

            {/* Loading State */}
            {loading && !data && (
                <div className={styles.emptyState}>
                    <Loader className={styles.spinner} size={24} />
                    <p>Loading key columns analysis...</p>
                </div>
            )}

            {/* Empty State */}
            {!loading && topColumns.length === 0 && (
                <div className={styles.emptyState}>
                    <AlertCircle size={32} />
                    <h3>No Key Columns Found</h3>
                    <p>Run an analysis to identify important columns based on query patterns.</p>
                </div>
            )}

            {/* Key Columns - Grouped View */}
            {topColumns.length > 0 && viewMode === 'grouped' && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                    {groupedByTable.map((group) => {
                        const isTableExpanded = expandedTables.has(group.tableName)
                        return (
                            <div
                                key={group.tableName}
                                style={{
                                    border: '1px solid var(--color-light-3)',
                                    borderRadius: 'var(--radius-md)',
                                    overflow: 'hidden'
                                }}
                            >
                                {/* Table Header */}
                                <div
                                    onClick={() => toggleTable(group.tableName)}
                                    style={{
                                        padding: '12px 16px',
                                        background: 'var(--color-light-1)',
                                        cursor: 'pointer',
                                        display: 'flex',
                                        alignItems: 'center',
                                        gap: '12px',
                                        borderBottom: isTableExpanded ? '1px solid var(--color-light-3)' : 'none'
                                    }}
                                >
                                    {isTableExpanded ? <ChevronDown size={18} /> : <ChevronRight size={18} />}
                                    <div style={{ flex: 1 }}>
                                        <div style={{ fontWeight: 600, fontSize: 'var(--font-size-md)' }}>
                                            {group.tableName}
                                        </div>
                                        <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)', marginTop: '2px' }}>
                                            {group.columns.length} key column{group.columns.length !== 1 ? 's' : ''}
                                            {group.slowQueryUsage > 0 && (
                                                <span style={{ color: 'var(--color-info)', marginLeft: '8px' }}>
                                                    • {group.slowQueryUsage.toLocaleString()} query run{group.slowQueryUsage !== 1 ? 's' : ''}
                                                </span>
                                            )}
                                            {group.distinctQueriesCount > 0 && (
                                                <span style={{ color: 'var(--color-success)', marginLeft: '8px' }}>
                                                    • {group.distinctQueriesCount.toLocaleString()} unique
                                                </span>
                                            )}
                                            {group.antiPatternCount > 0 && (
                                                <span style={{ color: 'var(--color-warning)', marginLeft: '8px' }}>
                                                    • {group.antiPatternCount} issue{group.antiPatternCount !== 1 ? 's' : ''}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                    <div style={{ display: 'flex', gap: '16px', alignItems: 'center' }}>
                                        <div style={{ textAlign: 'center' }}>
                                            <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>Columns</div>
                                            <div style={{ fontWeight: 600, fontSize: 'var(--font-size-lg)', color: 'var(--color-primary)' }}>
                                                {group.columns.length}
                                            </div>
                                        </div>
                                        <div style={{ textAlign: 'center' }}>
                                            <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>Total Usage</div>
                                            <div style={{ fontWeight: 600, fontSize: 'var(--font-size-lg)' }}>
                                                {group.totalUsage.toLocaleString()}
                                            </div>
                                        </div>
                                        {group.maxScore > 0 && (
                                            <div style={{ textAlign: 'center' }}>
                                                <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>Max Score</div>
                                                <div style={{
                                                    fontWeight: 600,
                                                    fontSize: 'var(--font-size-lg)',
                                                    color: group.maxScore >= 70 ? 'var(--color-success)' :
                                                        group.maxScore >= 40 ? 'var(--color-warning)' : 'var(--color-grey)'
                                                }}>
                                                    {group.maxScore.toFixed(0)}
                                                </div>
                                            </div>
                                        )}
                                    </div>
                                </div>

                                {/* Table Columns */}
                                {isTableExpanded && (
                                    <div style={{ overflowX: 'auto' }}>
                                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--font-size-sm)' }}>
                                            <thead>
                                                <tr style={{ borderBottom: '1px solid var(--color-light-3)', background: 'var(--color-light-1)' }}>
                                                    <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Column</th>
                                                    <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>Score</th>
                                                    <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>JOINs</th>
                                                    <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>WHERE</th>
                                                    <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>GROUP BY</th>
                                                    <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>ORDER BY</th>
                                                    <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>Query Runs</th>
                                                    <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>Unique Qry</th>
                                                    <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>Issues</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {group.columns.map((column) => {
                                                    const columnKey = `${column.tableName}.${column.columnName}`
                                                    const isExpanded = expandedRows.has(columnKey)
                                                    const hasAntiPatterns = column.hasAntiPatterns && column.antiPatterns?.length > 0

                                                    return (
                                                        <>
                                                            <tr
                                                                key={columnKey}
                                                                style={{
                                                                    borderBottom: '1px solid var(--color-light-2)',
                                                                    background: hasAntiPatterns ? 'rgba(255, 170, 0, 0.05)' : 'transparent',
                                                                    cursor: hasAntiPatterns ? 'pointer' : 'default'
                                                                }}
                                                                onClick={() => hasAntiPatterns && toggleRow(columnKey)}
                                                            >
                                                                <td style={{ padding: '10px' }}>
                                                                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                                                                        {hasAntiPatterns && (
                                                                            isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />
                                                                        )}
                                                                        <span style={{ fontWeight: 500 }}>{column.columnName}</span>
                                                                    </div>
                                                                </td>
                                                                <td style={{ padding: '10px', textAlign: 'center' }}>
                                                                    <span style={{
                                                                        fontWeight: 600,
                                                                        color: (column.enhancedImportanceScore || column.importanceScore) >= 70 ? 'var(--color-success)' :
                                                                            (column.enhancedImportanceScore || column.importanceScore) >= 40 ? 'var(--color-warning)' :
                                                                                'var(--color-grey)'
                                                                    }}>
                                                                        {(column.enhancedImportanceScore || column.importanceScore || 0).toFixed(0)}
                                                                    </span>
                                                                </td>
                                                                <td style={{ padding: '10px', textAlign: 'center' }}>{column.usageBreakdown?.joinCount || 0}</td>
                                                                <td style={{ padding: '10px', textAlign: 'center' }}>{column.usageBreakdown?.whereCount || 0}</td>
                                                                <td style={{ padding: '10px', textAlign: 'center' }}>{column.usageBreakdown?.groupByCount || 0}</td>
                                                                <td style={{ padding: '10px', textAlign: 'center' }}>{column.usageBreakdown?.orderByCount || 0}</td>
                                                                <td style={{ padding: '10px', textAlign: 'center' }}>
                                                                    {column.usageBreakdown?.slowQueryUsage > 0 ? (
                                                                        <span style={{ fontWeight: 600, color: 'var(--color-info)' }}>
                                                                            {column.usageBreakdown.slowQueryUsage}
                                                                        </span>
                                                                    ) : '-'}
                                                                </td>
                                                                <td style={{ padding: '10px', textAlign: 'center' }}>
                                                                    {column.usageBreakdown?.distinctQueriesCount > 0 ? (
                                                                        <span style={{ fontWeight: 600, color: 'var(--color-success)' }}>
                                                                            {column.usageBreakdown.distinctQueriesCount}
                                                                        </span>
                                                                    ) : '-'}
                                                                </td>
                                                                <td style={{ padding: '10px', textAlign: 'center' }}>
                                                                    {hasAntiPatterns && (
                                                                        <span style={{
                                                                            display: 'inline-flex',
                                                                            alignItems: 'center',
                                                                            gap: '4px',
                                                                            padding: '4px 8px',
                                                                            borderRadius: 'var(--radius-sm)',
                                                                            background: 'rgba(255, 170, 0, 0.15)',
                                                                            color: 'var(--color-warning)',
                                                                            fontSize: 'var(--font-size-xs)',
                                                                            fontWeight: 600
                                                                        }}>
                                                                            <AlertTriangle size={12} />
                                                                            {column.antiPatterns.length}
                                                                        </span>
                                                                    )}
                                                                </td>
                                                            </tr>
                                                            {/* Expanded row details would go here */}
                                                            {isExpanded && hasAntiPatterns && (
                                                                <tr key={`${columnKey}-details`}>
                                                                    <td colSpan="8" style={{ padding: '0 10px 10px 30px', background: 'var(--color-light-1)' }}>
                                                                        <div style={{
                                                                            border: '1px solid var(--color-light-3)',
                                                                            borderRadius: 'var(--radius-sm)',
                                                                            padding: '12px',
                                                                            marginTop: '8px'
                                                                        }}>
                                                                            {column.antiPatterns.map((pattern, idx) => (
                                                                                <div
                                                                                    key={pattern.id || idx}
                                                                                    style={{
                                                                                        marginBottom: idx < column.antiPatterns.length - 1 ? '12px' : '0',
                                                                                        paddingBottom: idx < column.antiPatterns.length - 1 ? '12px' : '0',
                                                                                        borderBottom: idx < column.antiPatterns.length - 1 ? '1px solid var(--color-light-3)' : 'none'
                                                                                    }}
                                                                                >
                                                                                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px' }}>
                                                                                        <span className={getSeverityClass(pattern.severity)} style={{
                                                                                            padding: '2px 6px',
                                                                                            borderRadius: 'var(--radius-xs)',
                                                                                            fontSize: 'var(--font-size-xs)',
                                                                                            fontWeight: 600
                                                                                        }}>
                                                                                            {pattern.severity}
                                                                                        </span>
                                                                                        <div style={{ flex: 1 }}>
                                                                                            <div style={{ fontWeight: 600 }}>{pattern.title}</div>
                                                                                            <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-dark-grey)', marginTop: '4px' }}>
                                                                                                {pattern.description}
                                                                                            </div>
                                                                                            <div style={{
                                                                                                marginTop: '8px',
                                                                                                padding: '8px',
                                                                                                background: 'var(--color-white)',
                                                                                                border: '1px solid var(--color-light-3)',
                                                                                                borderRadius: 'var(--radius-sm)',
                                                                                                fontSize: 'var(--font-size-xs)',
                                                                                                fontFamily: 'monospace'
                                                                                            }}>
                                                                                                <strong>Recommendation:</strong> {pattern.recommendation}
                                                                                            </div>
                                                                                        </div>
                                                                                    </div>
                                                                                </div>
                                                                            ))}
                                                                        </div>
                                                                    </td>
                                                                </tr>
                                                            )}
                                                        </>
                                                    )
                                                })}
                                            </tbody>
                                        </table>
                                    </div>
                                )}
                            </div>
                        )
                    })}
                </div>
            )}

            {/* Key Columns Table - Flat View */}
            {topColumns.length > 0 && viewMode === 'flat' && (
                <div style={{ overflowX: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--font-size-sm)' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid var(--color-light-3)' }}>
                                <th style={{ padding: '10px', textAlign: 'left', fontWeight: 600 }}>Column</th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>
                                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
                                        <span>Enhanced Score</span>
                                        <button
                                            type="button"
                                            className={`${styles.iconButton} ${styles.tooltipButton} ${styles.tooltipButtonBelow}`}
                                            data-tooltip-title="Enhanced Score"
                                            data-tooltip-body={
                                                'Usage frequency + recency\n' +
                                                'Distinct query breadth boost\n' +
                                                'Selectivity/skew/NULL adjustments\n' +
                                                'Key type + index signals\n' +
                                                'Capped 0–100'
                                            }
                                            aria-label="Enhanced score definition"
                                        >
                                            <Info size={14} />
                                        </button>
                                    </div>
                                </th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>
                                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
                                        <span>ML Score</span>
                                        <button
                                            type="button"
                                            className={`${styles.iconButton} ${styles.tooltipButton} ${styles.tooltipButtonBelow}`}
                                            data-tooltip-title="ML Score (heuristic)"
                                            data-tooltip-body={
                                                'Not a trained model\n' +
                                                'Usage count, JOINs, WHEREs\n' +
                                                'Selectivity + anti-patterns\n' +
                                                'Capped 0–100'
                                            }
                                            aria-label="ML score definition"
                                        >
                                            <Info size={14} />
                                        </button>
                                    </div>
                                </th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>Selectivity</th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>JOINs</th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>WHERE</th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>GROUP BY</th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>ORDER BY</th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>Query Runs</th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>Unique Qry</th>
                                <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>Issues</th>
                            </tr>
                        </thead>
                        <tbody>
                            {topColumns.map((column, index) => {
                                const columnKey = `${column.tableName}.${column.columnName}`
                                const isExpanded = expandedRows.has(columnKey)
                                const hasAntiPatterns = column.hasAntiPatterns && column.antiPatterns?.length > 0

                                return (
                                    <>
                                        <tr
                                            key={columnKey}
                                            style={{
                                                borderBottom: '1px solid var(--color-light-2)',
                                                background: hasAntiPatterns ? 'rgba(255, 170, 0, 0.05)' : 'transparent',
                                                cursor: hasAntiPatterns ? 'pointer' : 'default'
                                            }}
                                            onClick={() => hasAntiPatterns && toggleRow(columnKey)}
                                        >
                                            <td style={{ padding: '10px' }}>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                                                    {hasAntiPatterns && (
                                                        isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />
                                                    )}
                                                    <div>
                                                        <div style={{ fontWeight: 600 }}>{column.columnName}</div>
                                                        <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>
                                                            {column.tableName}
                                                        </div>
                                                    </div>
                                                </div>
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                <div>
                                                    <span style={{
                                                        fontWeight: 600,
                                                        color: column.enhancedImportanceScore >= 70 ? 'var(--color-success)' :
                                                            column.enhancedImportanceScore >= 40 ? 'var(--color-warning)' :
                                                                'var(--color-grey)'
                                                    }}>
                                                        {column.enhancedImportanceScore ? column.enhancedImportanceScore.toFixed(1) : column.importanceScore.toFixed(1)}
                                                    </span>
                                                    {column.frequencyScore && (
                                                        <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>
                                                            {column.usesPerDay && `${column.usesPerDay.toFixed(1)}/day`}
                                                        </div>
                                                    )}
                                                </div>
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {column.mlPredictionScore && (
                                                    <span style={{
                                                        fontWeight: 600,
                                                        color: column.mlPredictionScore >= 70 ? 'var(--color-dark-grey)' :
                                                            column.mlPredictionScore >= 40 ? 'var(--color-dark-grey)' : 'var(--color-grey)'
                                                    }}>
                                                        {column.mlPredictionScore.toFixed(0)}
                                                    </span>
                                                )}
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {column.selectivity ? (
                                                    <span style={{
                                                        color: column.selectivity > 0.5 ? 'var(--color-dark-grey)' :
                                                            column.selectivity > 0.1 ? 'var(--color-dark-grey)' : 'var(--color-danger)'
                                                    }}>
                                                        {(column.selectivity * 100).toFixed(1)}%
                                                    </span>
                                                ) : '-'}
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {column.usageBreakdown.joinCount || 0}
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {column.usageBreakdown.whereCount || 0}
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {column.usageBreakdown.groupByCount || 0}
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {column.usageBreakdown.orderByCount || 0}
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {column.usageBreakdown.slowQueryUsage > 0 ? (
                                                    <span style={{
                                                        fontWeight: 600,
                                                        color: 'var(--color-info)'
                                                    }}>
                                                        {column.usageBreakdown.slowQueryUsage}
                                                    </span>
                                                ) : (
                                                    <span style={{ color: 'var(--color-grey)' }}>-</span>
                                                )}
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {column.usageBreakdown.distinctQueriesCount > 0 ? (
                                                    <span style={{
                                                        fontWeight: 600,
                                                        color: 'var(--color-success)'
                                                    }}>
                                                        {column.usageBreakdown.distinctQueriesCount}
                                                    </span>
                                                ) : (
                                                    <span style={{ color: 'var(--color-grey)' }}>-</span>
                                                )}
                                            </td>
                                            <td style={{ padding: '10px', textAlign: 'center' }}>
                                                {hasAntiPatterns && (
                                                    <span style={{
                                                        display: 'inline-flex',
                                                        alignItems: 'center',
                                                        gap: '4px',
                                                        padding: '4px 8px',
                                                        borderRadius: 'var(--radius-sm)',
                                                        background: 'rgba(255, 170, 0, 0.15)',
                                                        color: 'var(--color-warning)',
                                                        fontSize: 'var(--font-size-xs)',
                                                        fontWeight: 600
                                                    }}>
                                                        <AlertTriangle size={12} />
                                                        {column.antiPatterns.length}
                                                    </span>
                                                )}
                                            </td>
                                        </tr>

                                        {/* Expanded Anti-patterns */}
                                        {isExpanded && hasAntiPatterns && (
                                            <tr key={`${columnKey}-details`}>
                                                <td colSpan="10" style={{ padding: '0 10px 10px 40px' }}>
                                                    {/* Enhanced Metrics Section */}
                                                    {(column.frequencyScore || column.recencyScore || column.selectivity) && (
                                                        <div style={{
                                                            marginBottom: '12px',
                                                            padding: '10px',
                                                            background: 'rgba(59, 130, 246, 0.05)',
                                                            border: '1px solid rgba(59, 130, 246, 0.2)',
                                                            borderRadius: 'var(--radius-sm)',
                                                            display: 'grid',
                                                            gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                                                            gap: '10px',
                                                            fontSize: 'var(--font-size-xs)'
                                                        }}>
                                                            {column.frequencyScore && (
                                                                <div>
                                                                    <strong>Frequency:</strong> {column.frequencyScore.toFixed(1)}
                                                                    {column.usesPerDay && ` (${column.usesPerDay.toFixed(1)}/day)`}
                                                                </div>
                                                            )}
                                                            {column.recencyScore && (
                                                                <div>
                                                                    <strong>Recency Score:</strong> {column.recencyScore.toFixed(1)}
                                                                </div>
                                                            )}
                                                            {column.selectivity && (
                                                                <div>
                                                                    <strong>Selectivity:</strong> {(column.selectivity * 100).toFixed(2)}%
                                                                    {column.distinctCount && column.totalRows && (
                                                                        <span style={{ color: 'var(--color-grey)' }}>
                                                                            {` (${column.distinctCount}/${column.totalRows})`}
                                                                        </span>
                                                                    )}
                                                                </div>
                                                            )}
                                                            {column.nullRatio !== undefined && column.nullRatio !== null && (
                                                                <div>
                                                                    <strong>NULL Ratio:</strong> {(column.nullRatio * 100).toFixed(1)}%
                                                                    {column.nullRatio > 0.5 && (
                                                                        <span style={{ color: 'var(--color-error)', marginLeft: '4px' }}>
                                                                            ⚠️ High
                                                                        </span>
                                                                    )}
                                                                    {column.nullRatio > 0.3 && column.nullRatio <= 0.5 && (
                                                                        <span style={{ color: 'var(--color-warning)', marginLeft: '4px' }}>
                                                                            ⚠️ Medium
                                                                        </span>
                                                                    )}
                                                                </div>
                                                            )}
                                                            {column.indexName && (
                                                                <div>
                                                                    <strong>Index:</strong> {column.indexName}
                                                                    {column.indexScanCount !== undefined && (
                                                                        <span style={{ color: 'var(--color-grey)' }}>
                                                                            {` (${column.indexScanCount} scans)`}
                                                                        </span>
                                                                    )}
                                                                </div>
                                                            )}
                                                            {column.skewCoefficient !== undefined && column.skewCoefficient !== null && (
                                                                <div>
                                                                    <strong>Data Skew:</strong> {(column.skewCoefficient * 100).toFixed(1)}%
                                                                    {column.skewCategory && (
                                                                        <span style={{
                                                                            marginLeft: '4px',
                                                                            color: column.skewCategory === 'EXTREME' ? 'var(--color-error)' :
                                                                                   column.skewCategory === 'HIGH' ? 'var(--color-warning)' :
                                                                                   'var(--color-grey)'
                                                                        }}>
                                                                            ({column.skewCategory})
                                                                        </span>
                                                                    )}
                                                                    {column.isHeavilySkewed && (
                                                                        <span style={{ color: 'var(--color-error)', marginLeft: '4px' }}>
                                                                            ⚠️ Highly skewed
                                                                        </span>
                                                                    )}
                                                                </div>
                                                            )}

                                                            {/* Key Classification */}
                                                            {column.keyType && column.keyType !== 'NON_KEY' && (
                                                                <div>
                                                                    <strong>Key Type:</strong>{' '}
                                                                    <span style={{
                                                                        padding: '2px 8px',
                                                                        borderRadius: 'var(--radius-xs)',
                                                                        fontSize: 'var(--font-size-xs)',
                                                                        fontWeight: 600,
                                                                        background: column.keyType === 'TRUE_KEY' ? 'rgba(34, 197, 94, 0.1)' :
                                                                                   column.keyType === 'SURROGATE_KEY' ? 'rgba(59, 130, 246, 0.1)' :
                                                                                   column.keyType === 'ACCIDENTAL_KEY' ? 'rgba(234, 179, 8, 0.1)' :
                                                                                   'rgba(156, 163, 175, 0.1)',
                                                                        color: column.keyType === 'TRUE_KEY' ? 'var(--color-success)' :
                                                                               column.keyType === 'SURROGATE_KEY' ? 'var(--color-info)' :
                                                                               column.keyType === 'ACCIDENTAL_KEY' ? 'var(--color-warning)' :
                                                                               'var(--color-grey)'
                                                                    }}>
                                                                        {column.keyType === 'TRUE_KEY' && '🔑 True Key'}
                                                                        {column.keyType === 'SURROGATE_KEY' && '🆔 Surrogate Key'}
                                                                        {column.keyType === 'ACCIDENTAL_KEY' && '⚠️ Accidental Key'}
                                                                    </span>
                                                                    {column.keyConfidence && (
                                                                        <span style={{
                                                                            marginLeft: '8px',
                                                                            color: 'var(--color-grey)',
                                                                            fontSize: 'var(--font-size-xs)'
                                                                        }}>
                                                                            ({(column.keyConfidence * 100).toFixed(0)}% confidence)
                                                                        </span>
                                                                    )}
                                                                </div>
                                                            )}
                                                        </div>
                                                    )}

                                                    {/* Top Values Section */}
                                                    {column.topValues && column.topValues.length > 0 && (
                                                        <div style={{
                                                            marginBottom: '12px',
                                                            padding: '10px',
                                                            background: 'rgba(139, 92, 246, 0.05)',
                                                            border: '1px solid rgba(139, 92, 246, 0.2)',
                                                            borderRadius: 'var(--radius-sm)',
                                                            fontSize: 'var(--font-size-xs)'
                                                        }}>
                                                            <div style={{ fontWeight: 600, marginBottom: '8px' }}>
                                                                📊 Top Frequent Values
                                                            </div>
                                                            <div style={{ display: 'grid', gap: '4px' }}>
                                                                {column.topValues.slice(0, 5).map((tv, idx) => (
                                                                    <div key={idx} style={{
                                                                        display: 'flex',
                                                                        justifyContent: 'space-between',
                                                                        alignItems: 'center'
                                                                    }}>
                                                                        <span style={{
                                                                            fontFamily: 'monospace',
                                                                            color: 'var(--color-dark-grey)',
                                                                            maxWidth: '200px',
                                                                            overflow: 'hidden',
                                                                            textOverflow: 'ellipsis',
                                                                            whiteSpace: 'nowrap'
                                                                        }}>
                                                                            {tv.value}
                                                                        </span>
                                                                        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                                                                            <span style={{ color: 'var(--color-grey)' }}>
                                                                                {tv.count.toLocaleString()} rows
                                                                            </span>
                                                                            <span style={{
                                                                                fontWeight: 600,
                                                                                color: tv.percentage > 50 ? 'var(--color-error)' :
                                                                                       tv.percentage > 30 ? 'var(--color-warning)' :
                                                                                       'var(--color-success)'
                                                                            }}>
                                                                                {tv.percentage}%
                                                                            </span>
                                                                        </div>
                                                                    </div>
                                                                ))}
                                                                {column.topValues.length > 5 && (
                                                                    <div style={{ color: 'var(--color-grey)', fontStyle: 'italic', marginTop: '4px' }}>
                                                                        + {column.topValues.length - 5} more values
                                                                    </div>
                                                                )}
                                                            </div>
                                                        </div>
                                                    )}

                                                    {/* Partitioning Recommendation */}
                                                    {column.isPartitionCandidate && (
                                                        <div style={{
                                                            marginBottom: '12px',
                                                            padding: '12px',
                                                            background: 'rgba(99, 102, 241, 0.05)',
                                                            border: '2px solid rgba(99, 102, 241, 0.3)',
                                                            borderRadius: 'var(--radius-sm)'
                                                        }}>
                                                            <div style={{
                                                                display: 'flex',
                                                                alignItems: 'center',
                                                                gap: '8px',
                                                                marginBottom: '8px'
                                                            }}>
                                                                <span style={{ fontSize: '16px' }}>🗂️</span>
                                                                <strong style={{ color: 'var(--color-info)' }}>
                                                                    Partitioning Candidate
                                                                </strong>
                                                                {column.partitioningType && (
                                                                    <span style={{
                                                                        padding: '2px 8px',
                                                                        borderRadius: 'var(--radius-xs)',
                                                                        fontSize: 'var(--font-size-xs)',
                                                                        fontWeight: 600,
                                                                        background: column.partitioningType === 'RANGE' ? 'rgba(34, 197, 94, 0.15)' :
                                                                                   column.partitioningType === 'LIST' ? 'rgba(59, 130, 246, 0.15)' :
                                                                                   'rgba(168, 85, 247, 0.15)',
                                                                        color: column.partitioningType === 'RANGE' ? 'var(--color-success)' :
                                                                               column.partitioningType === 'LIST' ? 'var(--color-info)' :
                                                                               'var(--color-purple)'
                                                                    }}>
                                                                        {column.partitioningType}
                                                                    </span>
                                                                )}
                                                                {column.partitioningScore && (
                                                                    <span style={{
                                                                        marginLeft: 'auto',
                                                                        fontSize: 'var(--font-size-xs)',
                                                                        color: 'var(--color-grey)',
                                                                        fontWeight: 600
                                                                    }}>
                                                                        Score: {column.partitioningScore.toFixed(0)}/100
                                                                    </span>
                                                                )}
                                                            </div>
                                                            {column.partitioningRecommendation && (
                                                                <div style={{
                                                                    fontSize: 'var(--font-size-xs)',
                                                                    color: 'var(--color-dark-grey)',
                                                                    lineHeight: '1.5',
                                                                    whiteSpace: 'pre-wrap'
                                                                }}>
                                                                    {column.partitioningRecommendation}
                                                                </div>
                                                            )}
                                                        </div>
                                                    )}

                                                    {/* Anti-patterns Section */}
                                                    <div style={{
                                                        border: '1px solid var(--color-light-3)',
                                                        borderRadius: 'var(--radius-sm)',
                                                        padding: '12px',
                                                        background: 'var(--color-light-1)'
                                                    }}>
                                                        {column.antiPatterns.map((pattern, idx) => (
                                                            <div
                                                                key={pattern.id || idx}
                                                                style={{
                                                                    marginBottom: idx < column.antiPatterns.length - 1 ? '12px' : '0',
                                                                    paddingBottom: idx < column.antiPatterns.length - 1 ? '12px' : '0',
                                                                    borderBottom: idx < column.antiPatterns.length - 1 ? '1px solid var(--color-light-3)' : 'none'
                                                                }}
                                                            >
                                                                <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', marginBottom: '6px' }}>
                                                                    <span className={getSeverityClass(pattern.severity)} style={{
                                                                        padding: '2px 6px',
                                                                        borderRadius: 'var(--radius-xs)',
                                                                        fontSize: 'var(--font-size-xs)',
                                                                        fontWeight: 600
                                                                    }}>
                                                                        {pattern.severity}
                                                                    </span>
                                                                    <div style={{ flex: 1 }}>
                                                                        <div style={{ fontWeight: 600, marginBottom: '4px' }}>
                                                                            {pattern.title}
                                                                        </div>
                                                                        <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-dark-grey)', marginBottom: '8px' }}>
                                                                            {pattern.description}
                                                                        </div>
                                                                        <div style={{
                                                                            padding: '8px',
                                                                            background: 'var(--color-white)',
                                                                            border: '1px solid var(--color-light-3)',
                                                                            borderRadius: 'var(--radius-sm)',
                                                                            fontSize: 'var(--font-size-xs)',
                                                                            fontFamily: 'monospace'
                                                                        }}>
                                                                            <strong>Recommendation:</strong> {pattern.recommendation}
                                                                        </div>
                                                                        {pattern.affectedQueriesCount > 0 && (
                                                                            <div style={{
                                                                                marginTop: '6px',
                                                                                fontSize: 'var(--font-size-xs)',
                                                                                color: 'var(--color-grey)'
                                                                            }}>
                                                                                Affects {pattern.affectedQueriesCount} queries
                                                                            </div>
                                                                        )}
                                                                    </div>
                                                                </div>
                                                            </div>
                                                        ))}
                                                    </div>
                                                </td>
                                            </tr>
                                        )}
                                    </>
                                )
                            })}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    )
}
