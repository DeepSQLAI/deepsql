'use client'

import { useMemo } from 'react'
import { Users, Clock, Database } from 'lucide-react'
import styles from './KeyCustomersCard.module.css'

function formatDuration(ms) {
    if (ms == null || ms === 0) return '—'
    if (ms < 1000) return `${Math.round(ms)}ms`
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
    return `${(ms / 60000).toFixed(1)}m`
}

function formatDbTime(ms) {
    if (ms == null || ms === 0) return '—'
    if (ms < 1000) return `${ms}ms`
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
    return `${(ms / 60000).toFixed(1)}m`
}

const KEY_TYPE_LABELS = {
    TRUE_KEY: { label: 'Key', tooltip: 'Primary key, unique key, or foreign key — a true identifier used in JOINs' },
    SURROGATE_KEY: { label: 'Surrogate', tooltip: 'Auto-generated ID (e.g. auto-increment, UUID) — not a business identifier' },
    ACCIDENTAL_KEY: { label: 'Accidental', tooltip: 'High cardinality column that looks like a key but is never used in JOINs' },
}

export default function KeyCustomersCard({
    keyCustomersData,
    isLoading,
    onCustomerClick,
    hideHeader = false,
}) {
    const keyCustomers = useMemo(
        () => keyCustomersData?.keyCustomers ?? [],
        [keyCustomersData]
    )

    if (isLoading) {
        return (
            <div className={hideHeader ? undefined : styles.card}>
                {!hideHeader && (
                    <div className={styles.header}>
                        <Users size={18} />
                        <div>
                            <h3 className={styles.title}>Key Customers</h3>
                            <p className={styles.subtitle}>Loading...</p>
                        </div>
                    </div>
                )}
                {hideHeader && <p className={styles.subtitle}>Loading...</p>}
            </div>
        )
    }

    if (!keyCustomersData || keyCustomers.length === 0) {
        return (
            <div className={hideHeader ? undefined : styles.card}>
                {!hideHeader && (
                    <div className={styles.header}>
                        <Users size={18} />
                        <div>
                            <h3 className={styles.title}>Key Customers</h3>
                            <p className={styles.subtitle}>Entities driving the most slow query load</p>
                        </div>
                    </div>
                )}
                <div className={styles.emptyState}>
                    <Database size={24} className={styles.emptyIcon} />
                    <p>No key customers identified yet.</p>
                    <p className={styles.emptyHint}>
                        Run slow query analysis with sample queries to identify key entities.
                    </p>
                </div>
            </div>
        )
    }

    return (
        <div className={hideHeader ? undefined : styles.card}>
            {!hideHeader && (
                <div className={styles.header}>
                    <Users size={18} />
                    <div>
                        <h3 className={styles.title}>Key Customers</h3>
                        <p className={styles.subtitle}>
                            Entities driving the most slow query load
                            {keyCustomersData.queriesCapped && (
                                <span className={styles.capBadge}>
                                    {' '}· top {keyCustomersData.totalSlowQueriesAnalyzed} queries analyzed
                                </span>
                            )}
                        </p>
                    </div>
                </div>
            )}

            <div className={styles.tableWrapper}>
                <table className={styles.table}>
                    <thead>
                        <tr>
                            <th className={styles.thColumn}>Column</th>
                            <th className={styles.thValue}>Value</th>
                            <th className={styles.thNumeric}># Slow</th>
                            <th className={styles.thNumeric}>Total DB Time</th>
                            <th className={styles.thNumeric}>Worst</th>
                        </tr>
                    </thead>
                    <tbody>
                        {keyCustomers.map((customer) => {
                            const hasCritical = customer.criticalCount > 0

                            return (
                                <tr
                                    key={customer.id}
                                    className={`${styles.row} ${onCustomerClick ? styles.rowClickable : ''}`}
                                    onClick={onCustomerClick ? () => onCustomerClick(
                                        customer.matchingQueryIds,
                                        `Slow queries for ${customer.columnName} = ${customer.displayValue}`
                                    ) : undefined}
                                >
                                    <td className={styles.tdColumn}>
                                        <span className={styles.columnName}>
                                            {customer.tableName && (
                                                <span className={styles.tableName}>{customer.tableName}.</span>
                                            )}
                                            {customer.columnName}
                                        </span>
                                        {customer.keyType && KEY_TYPE_LABELS[customer.keyType] && (
                                            <span className={styles.keyBadgeGroup}>
                                                <span className={styles.keyBadge}>
                                                    {KEY_TYPE_LABELS[customer.keyType].label}
                                                </span>
                                                <span className={styles.keyTooltipAnchor}>
                                                    <span className={styles.keyTooltipIcon}>?</span>
                                                    <span className={styles.keyTooltip}>
                                                        {KEY_TYPE_LABELS[customer.keyType].tooltip}
                                                    </span>
                                                </span>
                                            </span>
                                        )}
                                    </td>
                                    <td className={styles.tdValue}>
                                        <code className={styles.valueText}>{customer.displayValue}</code>
                                    </td>
                                    <td className={styles.tdNumeric}>
                                        <span className={hasCritical ? styles.countCritical : styles.count}>
                                            {customer.slowQueryCount}
                                        </span>
                                    </td>
                                    <td className={styles.tdNumeric}>
                                        {formatDbTime(customer.totalDbTimeMs)}
                                    </td>
                                    <td className={styles.tdNumeric}>
                                        <span className={styles.worstTime}>
                                            <Clock size={11} />
                                            {formatDuration(customer.worstQueryTimeMs)}
                                        </span>
                                    </td>
                                </tr>
                            )
                        })}
                    </tbody>
                </table>
            </div>
        </div>
    )
}
