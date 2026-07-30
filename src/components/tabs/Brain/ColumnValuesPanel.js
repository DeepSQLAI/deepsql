'use client'

import { useState, useMemo } from 'react'
import {
    AlertCircle,
    RefreshCw,
    Loader,
    ChevronDown,
    ChevronRight,
    Database,
    Check,
    Clock,
    Search,
    Upload
} from 'lucide-react'
import { useColumnValues } from './hooks/useColumnValues'
import { ActionGuard } from '@/components/ActionGuard'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Column Values Panel component
 * Shows cached low-cardinality column values used for better chat SQL generation
 */
export function ColumnValuesPanel({ connectionId, hideCTAs = false }) {

    const {
        data,
        stats,
        loading,
        refreshing,
        error,
        fetchColumnValues,
        refreshColumnValues,
        embedAllValues
    } = useColumnValues(connectionId)

    const [expandedTables, setExpandedTables] = useState(new Set())
    const [searchTerm, setSearchTerm] = useState('')

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

    // Group columns by table
    const groupedByTable = useMemo(() => {
        const groups = {}

        const filteredData = data.filter(col => {
            if (!searchTerm) return true
            const search = searchTerm.toLowerCase()
            return col.tableName?.toLowerCase().includes(search) ||
                   col.columnName?.toLowerCase().includes(search)
        })

        filteredData.forEach(col => {
            const tableName = col.tableName || 'Unknown'
            if (!groups[tableName]) {
                groups[tableName] = {
                    tableName,
                    columns: [],
                    embeddedCount: 0,
                    totalValues: 0
                }
            }
            groups[tableName].columns.push(col)
            if (col.embedded) {
                groups[tableName].embeddedCount++
            }
            groups[tableName].totalValues += col.distinctCount || 0
        })

        return Object.values(groups).sort((a, b) =>
            b.columns.length - a.columns.length
        )
    }, [data, searchTerm])

    // Parse JSON values safely
    const parseValues = (jsonString) => {
        try {
            if (!jsonString) return []
            return JSON.parse(jsonString)
        } catch {
            return []
        }
    }

    const formatDate = (dateStr) => {
        if (!dateStr) return 'Never'
        const date = new Date(dateStr)
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    }

    return (
        <div className={styles.brainPanel}>
            {!hideCTAs && (
                <div className={styles.brainHeader}>
                    <div>
                        <h3>Column Values</h3>
                        <p>Low-cardinality column values cached for better SQL generation</p>
                    </div>
                    {!hideCTAs && (
                        <div className={styles.brainHeaderActions}>
                        <ActionGuard action="refresh-column-values">
                            <button
                                className={styles.profileButton}
                                onClick={refreshColumnValues}
                                disabled={refreshing}
                                title="Refresh column values from database"
                            >
                                {refreshing ? <Loader className={styles.spinner} size={14} /> : <RefreshCw size={14} />}
                                <span>Refresh Values</span>
                            </button>
                        </ActionGuard>
                        <ActionGuard action="embed-column-values" mode="hide">
                            <button
                                className={styles.profileButton}
                                onClick={embedAllValues}
                                disabled={refreshing}
                                title="Embed unembedded values to Azure AI Search"
                            >
                                {refreshing ? <Loader className={styles.spinner} size={14} /> : <Upload size={14} />}
                                <span>Embed All</span>
                            </button>
                        </ActionGuard>
                        <button
                            className={styles.refreshButton}
                            onClick={fetchColumnValues}
                            disabled={loading}
                        >
                            {loading ? <Loader className={styles.spinner} size={14} /> : 'Reload'}
                        </button>
                    </div>
                    )}
                </div>
            )}

            {/* Stats Summary */}
            {stats && (
                <div style={{
                    display: 'flex',
                    gap: '24px',
                    padding: '16px',
                    background: 'var(--color-bg-secondary)',
                    borderRadius: 'var(--radius-sm)',
                    marginBottom: '16px'
                }}>
                    <div style={{ textAlign: 'center' }}>
                        <div style={{ fontSize: 'var(--font-size-2xl)', fontWeight: 700, color: 'var(--color-primary)' }}>
                            {stats.totalCached || 0}
                        </div>
                        <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>
                            Total Cached
                        </div>
                    </div>
                    <div style={{ textAlign: 'center' }}>
                        <div style={{ fontSize: 'var(--font-size-2xl)', fontWeight: 700, color: 'var(--color-success)' }}>
                            {stats.lowCardinalityCount || 0}
                        </div>
                        <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>
                            Low Cardinality
                        </div>
                    </div>
                    <div style={{ textAlign: 'center' }}>
                        <div style={{ fontSize: 'var(--font-size-2xl)', fontWeight: 700, color: 'var(--color-info)' }}>
                            {stats.embeddedCount || 0}
                        </div>
                        <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>
                            Embedded in AI Search
                        </div>
                    </div>
                    <div style={{ textAlign: 'center' }}>
                        <div style={{ fontSize: 'var(--font-size-2xl)', fontWeight: 700, color: 'var(--color-dark-grey)' }}>
                            &lt;{stats.threshold || 100}
                        </div>
                        <div style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-grey)' }}>
                            Cardinality Threshold
                        </div>
                    </div>
                </div>
            )}

            {/* Search */}
            <div style={{
                position: 'relative',
                marginBottom: '16px'
            }}>
                <Search
                    size={16}
                    style={{
                        position: 'absolute',
                        left: '12px',
                        top: '50%',
                        transform: 'translateY(-50%)',
                        color: 'var(--color-grey)'
                    }}
                />
                <input
                    type="text"
                    placeholder="Search tables or columns..."
                    value={searchTerm}
                    onChange={(e) => setSearchTerm(e.target.value)}
                    style={{
                        width: '100%',
                        padding: '10px 12px 10px 40px',
                        border: '1px solid var(--color-border)',
                        borderRadius: 'var(--radius-sm)',
                        fontSize: 'var(--font-size-sm)',
                        background: 'var(--color-bg)',
                        color: 'var(--color-text)'
                    }}
                />
            </div>

            {error && (
                <div className={styles.brainError}>
                    <AlertCircle size={14} />
                    <span>{error}</span>
                </div>
            )}

            {/* Info callout when no data */}
            {!loading && data.length === 0 && (
                <div className={styles.brainCallout}>
                    <div>
                        <strong>No column values cached yet.</strong>
                        <p style={{ margin: '8px 0 0', color: 'var(--color-grey)' }}>
                            Column values are automatically collected after running Key Column Analysis.
                            You can also click "Refresh Values" to manually trigger collection.
                        </p>
                    </div>
                </div>
            )}

            {/* Grouped Table View */}
            {groupedByTable.length > 0 && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {groupedByTable.map(group => (
                        <div
                            key={group.tableName}
                            style={{
                                border: '1px solid var(--color-border)',
                                borderRadius: 'var(--radius-sm)',
                                overflow: 'hidden'
                            }}
                        >
                            {/* Table Header */}
                            <button
                                onClick={() => toggleTable(group.tableName)}
                                style={{
                                    width: '100%',
                                    display: 'flex',
                                    alignItems: 'center',
                                    gap: '12px',
                                    padding: '12px 16px',
                                    background: 'var(--color-bg-secondary)',
                                    border: 'none',
                                    cursor: 'pointer',
                                    textAlign: 'left'
                                }}
                            >
                                {expandedTables.has(group.tableName) ?
                                    <ChevronDown size={16} /> :
                                    <ChevronRight size={16} />
                                }
                                <Database size={16} style={{ color: 'var(--color-info)' }} />
                                <span style={{ fontWeight: 600, flex: 1 }}>
                                    {group.tableName}
                                </span>
                                <span style={{
                                    fontSize: 'var(--font-size-xs)',
                                    color: 'var(--color-grey)',
                                    padding: '2px 8px',
                                    background: 'var(--color-bg)',
                                    borderRadius: 'var(--radius-xs)'
                                }}>
                                    {group.columns.length} columns
                                </span>
                                <span style={{
                                    fontSize: 'var(--font-size-xs)',
                                    color: group.embeddedCount === group.columns.length ? 'var(--color-success)' : 'var(--color-warning)',
                                    padding: '2px 8px',
                                    background: 'var(--color-bg)',
                                    borderRadius: 'var(--radius-xs)'
                                }}>
                                    {group.embeddedCount}/{group.columns.length} embedded
                                </span>
                            </button>

                            {/* Expanded Columns */}
                            {expandedTables.has(group.tableName) && (
                                <div style={{
                                    padding: '8px 16px 16px',
                                    display: 'flex',
                                    flexDirection: 'column',
                                    gap: '12px'
                                }}>
                                    {group.columns.map(col => {
                                        const values = parseValues(col.allValues || col.sampleValues)
                                        return (
                                            <div
                                                key={`${col.tableName}.${col.columnName}`}
                                                style={{
                                                    padding: '12px',
                                                    background: 'var(--color-bg-secondary)',
                                                    borderRadius: 'var(--radius-xs)',
                                                    border: '1px solid var(--color-border)'
                                                }}
                                            >
                                                {/* Column Header */}
                                                <div style={{
                                                    display: 'flex',
                                                    alignItems: 'center',
                                                    gap: '8px',
                                                    marginBottom: '8px'
                                                }}>
                                                    <span style={{ fontWeight: 600 }}>
                                                        {col.columnName}
                                                    </span>
                                                    {col.dataType && (
                                                        <span style={{
                                                            fontSize: 'var(--font-size-xs)',
                                                            color: 'var(--color-grey)',
                                                            padding: '2px 6px',
                                                            background: 'var(--color-bg)',
                                                            borderRadius: 'var(--radius-xs)'
                                                        }}>
                                                            {col.dataType}
                                                        </span>
                                                    )}
                                                    <span style={{
                                                        fontSize: 'var(--font-size-xs)',
                                                        color: 'var(--color-grey)'
                                                    }}>
                                                        {col.distinctCount || 0} distinct
                                                    </span>
                                                    {col.embedded ? (
                                                        <span style={{
                                                            display: 'flex',
                                                            alignItems: 'center',
                                                            gap: '4px',
                                                            fontSize: 'var(--font-size-xs)',
                                                            color: 'var(--color-success)',
                                                            marginLeft: 'auto'
                                                        }}>
                                                            <Check size={12} />
                                                            Embedded
                                                        </span>
                                                    ) : (
                                                        <span style={{
                                                            display: 'flex',
                                                            alignItems: 'center',
                                                            gap: '4px',
                                                            fontSize: 'var(--font-size-xs)',
                                                            color: 'var(--color-warning)',
                                                            marginLeft: 'auto'
                                                        }}>
                                                            <Clock size={12} />
                                                            Pending
                                                        </span>
                                                    )}
                                                </div>

                                                {/* Values */}
                                                {values.length > 0 && (
                                                    <div style={{
                                                        display: 'flex',
                                                        flexWrap: 'wrap',
                                                        gap: '6px',
                                                        marginBottom: '8px'
                                                    }}>
                                                        {values.slice(0, 20).map((val, idx) => (
                                                            <span
                                                                key={idx}
                                                                style={{
                                                                    padding: '4px 8px',
                                                                    background: 'var(--color-bg)',
                                                                    border: '1px solid var(--color-border)',
                                                                    borderRadius: 'var(--radius-xs)',
                                                                    fontSize: 'var(--font-size-xs)',
                                                                    fontFamily: 'var(--font-mono)',
                                                                    maxWidth: '200px',
                                                                    overflow: 'hidden',
                                                                    textOverflow: 'ellipsis',
                                                                    whiteSpace: 'nowrap'
                                                                }}
                                                                title={val}
                                                            >
                                                                '{val}'
                                                            </span>
                                                        ))}
                                                        {values.length > 20 && (
                                                            <span style={{
                                                                padding: '4px 8px',
                                                                fontSize: 'var(--font-size-xs)',
                                                                color: 'var(--color-grey)',
                                                                fontStyle: 'italic'
                                                            }}>
                                                                +{values.length - 20} more
                                                            </span>
                                                        )}
                                                    </div>
                                                )}

                                                {/* Metadata */}
                                                <div style={{
                                                    display: 'flex',
                                                    gap: '16px',
                                                    fontSize: 'var(--font-size-xs)',
                                                    color: 'var(--color-grey)'
                                                }}>
                                                    <span>Analyzed: {formatDate(col.analyzedAt)}</span>
                                                    {col.embeddedAt && (
                                                        <span>Embedded: {formatDate(col.embeddedAt)}</span>
                                                    )}
                                                </div>
                                            </div>
                                        )
                                    })}
                                </div>
                            )}
                        </div>
                    ))}
                </div>
            )}

            {/* How it helps section */}
            <div style={{
                marginTop: '24px',
                padding: '16px',
                background: 'rgba(59, 130, 246, 0.05)',
                border: '1px solid rgba(59, 130, 246, 0.2)',
                borderRadius: 'var(--radius-sm)'
            }}>
                <h4 style={{ margin: '0 0 8px', fontSize: 'var(--font-size-sm)' }}>
                    How Column Values Improve SQL Generation
                </h4>
                <ul style={{
                    margin: 0,
                    paddingLeft: '20px',
                    fontSize: 'var(--font-size-xs)',
                    color: 'var(--color-dark-grey)',
                    lineHeight: 1.6
                }}>
                    <li><strong>Exact Value Matching:</strong> Agent uses 'confirmed' not 'Confirmed' or 'CONFIRMED'</li>
                    <li><strong>Better Filters:</strong> Suggests valid WHERE clause values automatically</li>
                    <li><strong>RAG Retrieval:</strong> Embedded values help answer "What are valid statuses?" questions</li>
                    <li><strong>Fewer Errors:</strong> No more queries returning 0 rows due to typos</li>
                </ul>
            </div>
        </div>
    )
}
