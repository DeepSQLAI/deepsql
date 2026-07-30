'use client'

import { TrendingDown } from 'lucide-react'
import styles from './CompactOptimizationCard.module.css'

/**
 * Compact optimization card with numbered prefix for displaying index recommendations.
 *
 * @param {number} index - The card index (1, 2, 3, etc.)
 * @param {string} title - Issue type (e.g., "Missing Composite Index")
 * @param {number} improvement - Estimated improvement percentage
 * @param {string} tableName - The affected table name
 * @param {string} sql - The suggested SQL to apply
 * @param {function} onApply - Optional callback when Apply button is clicked
 */
export default function CompactOptimizationCard({
    index,
    title,
    improvement,
    tableName,
    sql,
    onApply,
}) {
    const formattedIndex = String(index).padStart(3, '0')

    return (
        <div className={styles.card}>
            <div className={styles.header}>
                <span className={styles.index}>{formattedIndex}</span>
                <span className={styles.title}>{title}</span>
                <span className={styles.tableName}>{tableName}</span>
            </div>

            <div className={styles.improvementRow}>
                <span className={styles.improvementBadge}>
                    <TrendingDown size={12} />
                    <span className={styles.arrow}>↓</span>
                    {improvement}%
                </span>
                <span className={styles.improvementLabel}>estimated improvement</span>
            </div>

            {sql && (
                <div className={styles.codeBlock}>
                    <code>{sql}</code>
                </div>
            )}

            {onApply && (
                <button className={styles.applyBtn} onClick={onApply}>
                    Apply
                </button>
            )}
        </div>
    )
}
