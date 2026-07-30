'use client'

import { useMemo } from 'react'
import { X, Users, AlertTriangle, ArrowRight } from 'lucide-react'
import styles from './SkewListModal.module.css'

const RISK_ORDER = { HIGH: 0, MEDIUM: 1, LOW: 2 }

function formatPct(value) {
    if (value == null || Number.isNaN(value)) return '—'
    return `${Number(value).toFixed(1)}%`
}

export default function SkewListModal({ open, onClose, skewItems, onViewQueries }) {
    const sorted = useMemo(() => {
        if (!skewItems?.length) return []
        return [...skewItems].sort((a, b) => {
            const riskDiff = (RISK_ORDER[a.riskLevel] ?? 9) - (RISK_ORDER[b.riskLevel] ?? 9)
            if (riskDiff !== 0) return riskDiff
            return (b.dominancePct ?? 0) - (a.dominancePct ?? 0)
        })
    }, [skewItems])

    const summary = useMemo(() => {
        const counts = { HIGH: 0, MEDIUM: 0, LOW: 0 }
        let totalSlowQueries = 0
        for (const item of sorted) {
            counts[item.riskLevel] = (counts[item.riskLevel] || 0) + 1
            totalSlowQueries += item.slowQueryCount ?? 0
        }
        return { ...counts, totalSlowQueries }
    }, [sorted])

    if (!open) return null

    return (
        <div className={styles.overlay} onClick={onClose}>
            <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
                {/* Header */}
                <div className={styles.header}>
                    <div>
                        <h2 className={styles.title}>
                            <Users size={18} />
                            Skew & Dominance
                        </h2>
                        <p className={styles.subtitle}>
                            Column values that appear disproportionately in slow queries, pointing to data hotspots or missing indexes.
                        </p>
                    </div>
                    <button type="button" className={styles.closeBtn} onClick={onClose}>
                        <X size={18} />
                    </button>
                </div>

                {/* Summary bar */}
                <div className={styles.summaryBar}>
                    <div className={styles.summaryItem}>
                        <span className={styles.summaryLabel}>Skewed Entities</span>
                        <span className={styles.summaryValue}>{sorted.length}</span>
                    </div>
                    <div className={styles.summaryItem}>
                        <span className={styles.summaryLabel}>High Risk</span>
                        <span className={summary.HIGH > 0 ? styles.summaryValueCritical : styles.summaryValue}>
                            {summary.HIGH}
                        </span>
                    </div>
                    <div className={styles.summaryItem}>
                        <span className={styles.summaryLabel}>Medium Risk</span>
                        <span className={summary.MEDIUM > 0 ? styles.summaryValueWarn : styles.summaryValue}>
                            {summary.MEDIUM}
                        </span>
                    </div>
                    <div className={styles.summaryItem}>
                        <span className={styles.summaryLabel}>Slow Queries</span>
                        <span className={styles.summaryValue}>{summary.totalSlowQueries}</span>
                    </div>
                </div>

                {/* List */}
                <div className={styles.list}>
                    {sorted.length === 0 ? (
                        <div className={styles.noResults}>No parameter skew detected in the analysis window.</div>
                    ) : (
                        sorted.map((item) => {
                            const riskClass = item.riskLevel === 'HIGH' ? styles.riskHigh
                                : item.riskLevel === 'MEDIUM' ? styles.riskMedium
                                : styles.riskLow
                            return (
                                <div key={item.id} className={styles.row}>
                                    <div className={styles.rowMain}>
                                        <div className={styles.rowHeader}>
                                            <span className={styles.entityName}>
                                                {item.columnName} = {item.displayValue}
                                            </span>
                                            <span className={`${styles.riskBadge} ${riskClass}`}>
                                                {item.riskLevel}
                                            </span>
                                        </div>
                                        <span className={styles.tablePath}>{item.tableName}.{item.columnName}</span>
                                    </div>

                                    <div className={styles.rowMetrics}>
                                        <div className={styles.metric}>
                                            <span className={styles.metricLabel}>Dominance</span>
                                            <span className={styles.metricValue}>{formatPct(item.dominancePct)}</span>
                                        </div>
                                        <div className={styles.metric}>
                                            <span className={styles.metricLabel}>Slow</span>
                                            <span className={styles.metricValue}>{item.slowQueryCount ?? 0}</span>
                                        </div>
                                        {item.criticalCount > 0 && (
                                            <div className={styles.metric}>
                                                <span className={styles.metricLabel}>Critical</span>
                                                <span className={styles.metricValueCritical}>{item.criticalCount}</span>
                                            </div>
                                        )}
                                        {item.highCount > 0 && (
                                            <div className={styles.metric}>
                                                <span className={styles.metricLabel}>High</span>
                                                <span className={styles.metricValueWarn}>{item.highCount}</span>
                                            </div>
                                        )}
                                    </div>

                                    {item.matchingQueryIds?.length > 0 && onViewQueries && (
                                        <button
                                            type="button"
                                            className={styles.viewQueriesBtn}
                                            onClick={() => onViewQueries(item.matchingQueryIds, `Slow queries for ${item.columnName} = ${item.displayValue}`)}
                                        >
                                            View queries <ArrowRight size={12} />
                                        </button>
                                    )}
                                </div>
                            )
                        })
                    )}
                </div>
            </div>
        </div>
    )
}
