'use client'

import { useState } from 'react'
import { CheckCircle, XCircle, Clock, AlertCircle, ChevronDown, ChevronRight } from 'lucide-react'
import { formatTimestamp, formatDuration } from './utils/formatUtils'
import styles from './TrainingHistory.module.css'

/**
 * Training history panel showing past training runs
 * Displays status, duration, errors, and detailed information
 */
export function TrainingHistory({ history = [] }) {
    const [expandedId, setExpandedId] = useState(null)

    if (history.length === 0) {
        return (
            <div className={styles.historyContainer}>
                <h3>Training History</h3>
                <div className={styles.emptyState}>
                    <Clock size={32} />
                    <p>No training history yet</p>
                    <span>Start training to see results here</span>
                </div>
            </div>
        )
    }

    const toggleExpand = (id) => {
        setExpandedId(expandedId === id ? null : id)
    }

    const getStatusIcon = (status) => {
        switch (status) {
            case 'COMPLETED':
                return <CheckCircle size={18} className={styles.iconSuccess} />
            case 'FAILED':
                return <XCircle size={18} className={styles.iconError} />
            case 'CANCELLED':
                return <XCircle size={18} className={styles.iconCancelled} />
            case 'RUNNING':
                return <Clock size={18} className={styles.iconRunning} />
            default:
                return <AlertCircle size={18} className={styles.iconWarning} />
        }
    }

    const getStatusClass = (status) => {
        switch (status) {
            case 'COMPLETED':
                return styles.statusSuccess
            case 'FAILED':
                return styles.statusError
            case 'CANCELLED':
                return styles.statusCancelled
            case 'RUNNING':
                return styles.statusRunning
            default:
                return styles.statusWarning
        }
    }

    const getDurationMs = (entry) => {
        if (!entry?.startedAt || !entry?.completedAt) {
            return null
        }
        const start = new Date(entry.startedAt).getTime()
        const end = new Date(entry.completedAt).getTime()
        if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
            return null
        }
        return end - start
    }

    return (
        <div className={styles.historyContainer}>
            <div className={styles.header}>
                <h3>Training History</h3>
                <span className={styles.count}>{history.length} runs</span>
            </div>

            <div className={styles.historyList}>
                {history.map((entry) => {
                    const isExpanded = expandedId === entry.id
                    const durationMs = getDurationMs(entry)
                    const hasError = entry.status === 'FAILED' && (entry.error || entry.message)

                    return (
                        <div key={entry.id} className={styles.historyItem}>
                            <div
                                className={styles.historyRow}
                                onClick={() => toggleExpand(entry.id)}
                            >
                                <div className={styles.expandIcon}>
                                    {isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                                </div>

                                <div className={styles.statusIcon}>
                                    {getStatusIcon(entry.status)}
                                </div>

                                <div className={styles.historyInfo}>
                                    <div className={styles.historyMain}>
                                        <span className={`${styles.status} ${getStatusClass(entry.status)}`}>
                                            {entry.status || 'Unknown'}
                                        </span>
                                        <span className={styles.timestamp}>
                                            {formatTimestamp(entry.startedAt || entry.createdAt)}
                                        </span>
                                    </div>
                                    <div className={styles.historyMeta}>
                                        {entry.processedTables !== undefined && entry.totalTables !== undefined && (
                                            <span>{entry.processedTables}/{entry.totalTables} tables</span>
                                        )}
                                        {durationMs !== null && (
                                            <span>Duration: {formatDuration(durationMs)}</span>
                                        )}
                                    </div>
                                </div>

                                {hasError && (
                                    <div className={styles.errorBadge}>
                                        <AlertCircle size={14} />
                                        <span>Error</span>
                                    </div>
                                )}
                            </div>

                            {isExpanded && (
                                <div className={styles.historyDetails}>
                                    <div className={styles.detailsGrid}>
                                        <div className={styles.detailItem}>
                                            <span className={styles.detailLabel}>Status</span>
                                            <span className={styles.detailValue}>{entry.status || '--'}</span>
                                        </div>

                                        <div className={styles.detailItem}>
                                            <span className={styles.detailLabel}>Started</span>
                                            <span className={styles.detailValue}>
                                                {formatTimestamp(entry.startedAt)}
                                            </span>
                                        </div>

                                        <div className={styles.detailItem}>
                                            <span className={styles.detailLabel}>Completed</span>
                                            <span className={styles.detailValue}>
                                                {formatTimestamp(entry.completedAt)}
                                            </span>
                                        </div>

                                        <div className={styles.detailItem}>
                                            <span className={styles.detailLabel}>Duration</span>
                                            <span className={styles.detailValue}>
                                                {durationMs ? formatDuration(durationMs) : '--'}
                                            </span>
                                        </div>

                                        <div className={styles.detailItem}>
                                            <span className={styles.detailLabel}>Tables Processed</span>
                                            <span className={styles.detailValue}>
                                                {entry.processedTables ?? '--'} / {entry.totalTables ?? '--'}
                                            </span>
                                        </div>

                                        <div className={styles.detailItem}>
                                            <span className={styles.detailLabel}>Progress</span>
                                            <span className={styles.detailValue}>
                                                {entry.progressPercent ? `${entry.progressPercent.toFixed(1)}%` : '--'}
                                            </span>
                                        </div>
                                    </div>

                                    {entry.message && (
                                        <div className={styles.detailMessage}>
                                            <span className={styles.detailLabel}>Message</span>
                                            <div className={styles.messageBox}>
                                                {entry.message}
                                            </div>
                                        </div>
                                    )}

                                    {entry.error && (
                                        <div className={styles.detailError}>
                                            <span className={styles.detailLabel}>Error Details</span>
                                            <div className={styles.errorBox}>
                                                <AlertCircle size={16} />
                                                <span>{entry.error}</span>
                                            </div>
                                        </div>
                                    )}

                                    {entry.tableTimings && (
                                        <div className={styles.tableTimings}>
                                            <span className={styles.detailLabel}>Table Processing Times</span>
                                            <div className={styles.timingsTable}>
                                                <div className={styles.timingsHeader}>
                                                    <span>Table</span>
                                                    <span>Duration</span>
                                                    <span>Columns</span>
                                                    <span>Rows</span>
                                                </div>
                                                {parseTableTimings(entry.tableTimings).slice(0, 10).map((timing, i) => (
                                                    <div key={i} className={styles.timingsRow}>
                                                        <span>{timing.tableName || '--'}</span>
                                                        <span>{formatDuration(timing.durationMs)}</span>
                                                        <span>{timing.columnCount ?? '--'}</span>
                                                        <span>{timing.rowCount ?? '--'}</span>
                                                    </div>
                                                ))}
                                                {parseTableTimings(entry.tableTimings).length > 10 && (
                                                    <div className={styles.timingsMore}>
                                                        ... and {parseTableTimings(entry.tableTimings).length - 10} more tables
                                                    </div>
                                                )}
                                            </div>
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    )
                })}
            </div>
        </div>
    )
}

// Helper to parse table timings (could be JSON string or array)
function parseTableTimings(tableTimings) {
    if (!tableTimings) return []
    if (Array.isArray(tableTimings)) return tableTimings

    try {
        const parsed = JSON.parse(tableTimings)
        return Array.isArray(parsed) ? parsed : []
    } catch (err) {
        console.error('Failed to parse table timings:', err)
        return []
    }
}
