'use client'

import styles from './CompactStatCard.module.css'

/**
 * Compact stat card with variant colors for Performance Insights.
 *
 * @param {string} title - The label for the stat (e.g., "Optimization Potential")
 * @param {string|number} value - The main value to display
 * @param {string} subtitle - Optional subtitle text
 * @param {string} subtitleColor - Color for subtitle ('green' | 'default')
 * @param {string} variant - Card variant ('default' | 'success' | 'warning')
 */
export default function CompactStatCard({
    title,
    value,
    subtitle,
    subtitleColor = 'default',
    variant = 'default',
}) {
    const variantClass = {
        default: '',
        success: styles.success,
        warning: styles.warning,
    }[variant] || ''

    const subtitleColorClass = subtitleColor === 'green' ? styles.subtitleGreen : ''

    return (
        <div className={`${styles.card} ${variantClass}`}>
            <div className={styles.label}>{title}</div>
            <div className={styles.value}>{value}</div>
            {subtitle && (
                <div className={`${styles.subtitle} ${subtitleColorClass}`}>
                    {subtitle}
                </div>
            )}
        </div>
    )
}
