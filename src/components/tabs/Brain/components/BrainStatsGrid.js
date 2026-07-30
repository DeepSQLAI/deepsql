'use client'

import styles from './BrainStatsGrid.module.css'

/**
 * Compact stats grid for Brain Overview - shows 4 key metrics.
 */
export default function BrainStatsGrid({
    coveragePercent = 0,
    criticalIssues = 0,
    missingIndexes = 0,
    orphanedTables = 0,
}) {
    const stats = [
        {
            label: 'Coverage',
            value: `${coveragePercent}%`,
            subtitle: 'columns profiled',
            variant: coveragePercent >= 80 ? 'success' : coveragePercent >= 50 ? 'default' : 'warning',
        },
        {
            label: 'Issues',
            value: criticalIssues,
            subtitle: 'need attention',
            variant: criticalIssues === 0 ? 'success' : criticalIssues <= 5 ? 'default' : 'warning',
        },
        {
            label: 'Missing Indexes',
            value: missingIndexes,
            subtitle: 'recommended',
            variant: missingIndexes === 0 ? 'success' : missingIndexes <= 3 ? 'default' : 'warning',
        },
        {
            label: 'Orphaned Tables',
            value: orphanedTables,
            subtitle: 'no relationships',
            variant: orphanedTables === 0 ? 'success' : orphanedTables <= 2 ? 'default' : 'warning',
        },
    ]

    return (
        <div className={styles.grid}>
            {stats.map((stat) => (
                <div
                    key={stat.label}
                    className={`${styles.card} ${styles[stat.variant] || ''}`}
                >
                    <div className={styles.label}>{stat.label}</div>
                    <div className={styles.value}>{stat.value}</div>
                    <div className={styles.subtitle}>{stat.subtitle}</div>
                </div>
            ))}
        </div>
    )
}
