'use client'

import { useState } from 'react'
import { AlertTriangle, Lightbulb, TrendingUp, Database, ListTree, Columns, CheckCircle, X, ArrowRight } from 'lucide-react'
import styles from './BrainInsightsPanel.module.css'

/**
 * Quick insights panel showing all insights and actions as cards.
 * Insights with tables show a popup modal with affected table names.
 */
export default function BrainInsightsPanel({ insights = [], actions = [] }) {
    const [selectedInsight, setSelectedInsight] = useState(null)

    const getInsightIcon = (type) => {
        const iconProps = { size: 20, strokeWidth: 2 }
        switch (type) {
            case 'warning':
                return <AlertTriangle {...iconProps} />
            case 'trend':
                return <TrendingUp {...iconProps} />
            case 'table':
                return <Database {...iconProps} />
            case 'index':
                return <ListTree {...iconProps} />
            case 'column':
                return <Columns {...iconProps} />
            case 'success':
                return <CheckCircle {...iconProps} />
            default:
                return <Lightbulb {...iconProps} />
        }
    }

    const getWrapperClass = (type) => {
        switch (type) {
            case 'warning': return styles.iconWarning
            case 'trend': return styles.iconTrend
            case 'table': return styles.iconTable
            case 'index': return styles.iconIndex
            case 'column': return styles.iconColumn
            case 'success': return styles.iconSuccess
            default: return styles.iconInfo
        }
    }

    const parseInsightText = (text) => {
        const match = text.match(/^(\d+)\s+(.*)$/)
        if (match) {
            return { number: match[1], label: match[2] }
        }
        return { number: null, label: text }
    }

    const getInsightDescription = (text) => {
        if (!text) return null
        const t = text.toLowerCase()

        if (t.includes('god table')) return {
            what: 'A "god table" is a table with an unusually high number of columns (50+). It tries to store too many different concepts in one place.',
            why: 'God tables are slow to query, hard to index efficiently, and difficult to maintain. They often cause full-row fetches when only a few columns are needed, wasting I/O and memory.'
        }
        if (t.includes('orphaned table')) return {
            what: 'Orphaned tables have no foreign key relationships to any other table in the schema.',
            why: 'These tables are often unused leftovers from old features, migrations, or integrations. They add clutter, consume storage, and can mislead developers. Worth reviewing for cleanup.'
        }
        if (t.includes('missing index') || t.includes('columns missing index')) return {
            what: 'These columns are frequently used in WHERE clauses but have no index, so the database scans the entire table to find matching rows.',
            why: 'Missing indexes on filter columns are one of the most common causes of slow queries. Adding the right index can cut query time from seconds to milliseconds.'
        }
        if (t.includes('read-heavy table')) return {
            what: 'Read-heavy tables receive significantly more SELECT queries than INSERT/UPDATE/DELETE operations.',
            why: 'These tables benefit most from caching, read replicas, and covering indexes. Over-indexing is less of a concern here since reads dominate.'
        }
        if (t.includes('write-heavy table')) return {
            what: 'Write-heavy tables receive a high volume of INSERT, UPDATE, or DELETE operations relative to reads.',
            why: 'Too many indexes on write-heavy tables slow down every write. These tables may also be candidates for partitioning or archiving to manage growth.'
        }
        if (t.includes('partition')) return {
            what: 'These tables are large enough that splitting them by a time range or value range (partitioning) would improve performance.',
            why: 'Partitioning allows the database to skip entire chunks of data during queries, dramatically reducing scan size and improving both query speed and maintenance operations like archiving.'
        }
        if (t.includes('key column')) return {
            what: 'These are the most frequently queried columns across your workload, based on actual query patterns.',
            why: 'Ensuring these columns are properly indexed and have up-to-date statistics is critical. They appear in the most queries and have the highest performance leverage.'
        }
        if (t.includes('columns used in join')) return {
            what: 'These columns appear in JOIN conditions across your queries.',
            why: 'JOIN columns without indexes force full scans of both tables being joined. Indexing these columns is one of the highest-impact optimizations you can make.'
        }
        if (t.includes('columns used in where')) return {
            what: 'These columns appear frequently in WHERE clause filters.',
            why: 'High-frequency filter columns are prime candidates for indexes. Even a single missing index here can affect many queries simultaneously.'
        }
        return null
    }

    const hasContent = insights.length > 0 || actions.length > 0

    if (!hasContent) {
        return (
            <div className={styles.panel}>
                <div className={styles.empty}>
                    <Lightbulb size={24} />
                    <span>Run analysis to discover insights about your database</span>
                </div>
            </div>
        )
    }

    return (
        <div className={styles.panel}>
            <div className={styles.grid}>
                {insights.map((insight, idx) => {
                    const hasTables = insight.tables && insight.tables.length > 0
                    const { number, label } = parseInsightText(insight.text)
                    
                    return (
                        <div
                            key={`insight-${idx}`}
                            className={styles.card}
                            onClick={hasTables ? () => setSelectedInsight(insight) : undefined}
                            style={{ cursor: hasTables ? 'pointer' : 'default' }}
                        >
                            <div className={styles.cardHeader}>
                                <div className={`${styles.iconWrapper} ${getWrapperClass(insight.type)}`}>
                                    {getInsightIcon(insight.type)}
                                </div>
                            </div>
                            
                            <div>
                                {number && <div className={styles.metricValue}>{number}</div>}
                                <div className={styles.metricLabel} style={!number ? { fontSize: '16px', fontWeight: 600, color: '#111827' } : {}}>
                                    {label}
                                </div>
                            </div>

                            {hasTables && (
                                <div className={styles.cardFooter}>
                                    <button className={styles.viewDetailsBtn}>
                                        View Details <ArrowRight size={14} />
                                    </button>
                                </div>
                            )}
                        </div>
                    )
                })}
                
                {actions.map((action, idx) => (
                    <div key={`action-${idx}`} className={`${styles.card} ${styles.actionCard}`}>
                         <div className={styles.cardHeader}>
                            <span className={styles.actionBadge}>Action #{idx + 1}</span>
                        </div>
                        <div className={styles.metricLabel} style={{ fontSize: '16px', fontWeight: 600, color: '#92400E' }}>
                            {action.title}
                        </div>
                    </div>
                ))}
            </div>

            {/* Modal */}
            {selectedInsight && (
                <div className={styles.modalOverlay} onClick={() => setSelectedInsight(null)}>
                    <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
                        <div className={styles.modalHeader}>
                            <div className={styles.modalTitle}>
                                <div className={`${styles.iconWrapper} ${getWrapperClass(selectedInsight.type)}`} style={{ width: 32, height: 32 }}>
                                    {getInsightIcon(selectedInsight.type)}
                                </div>
                                <span>{selectedInsight.text}</span>
                            </div>
                            <button
                                className="panelToggleButton"
                                onClick={() => setSelectedInsight(null)}
                                aria-label="Close"
                                style={{ color: '#6B7280', width: 32, height: 32, minHeight: 32 }}
                            >
                                <X size={18} />
                            </button>
                        </div>
                        <div className={styles.modalBody}>
                            {(() => {
                                const desc = getInsightDescription(selectedInsight.text)
                                return desc ? (
                                    <div className={styles.insightDescription}>
                                        <div className={styles.descriptionBlock}>
                                            <span className={styles.descriptionLabel}>What this means</span>
                                            <p className={styles.descriptionText}>{desc.what}</p>
                                        </div>
                                        <div className={styles.descriptionBlock}>
                                            <span className={styles.descriptionLabel}>Why it matters</span>
                                            <p className={styles.descriptionText}>{desc.why}</p>
                                        </div>
                                    </div>
                                ) : null
                            })()}
                            <div className={styles.tableList}>
                                {selectedInsight.tables.map((table, idx) => (
                                    <div key={idx} className={styles.tableItem}>
                                        {table}
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}
