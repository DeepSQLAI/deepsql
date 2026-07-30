'use client'

import { Lightbulb, AlertTriangle, TrendingUp, Archive, Info } from 'lucide-react'
import styles from './InsightsList.module.css'

/**
 * List of AI-generated insights with typed icons.
 *
 * @param {Array<{text: string, type: 'info'|'warning'|'trend'|'archive'}>} insights - List of insights
 * @param {string} title - Section title
 */
export default function InsightsList({ insights = [], title = 'AI Insights' }) {
    if (!insights || insights.length === 0) {
        return (
            <div className={styles.container}>
                <div className={styles.header}>
                    <h3 className={styles.title}>{title}</h3>
                </div>
                <div className={styles.emptyState}>
                    No insights available yet
                </div>
            </div>
        )
    }

    const getIcon = (type) => {
        const iconProps = { size: 14, className: styles.icon }

        switch (type) {
            case 'warning':
                return <AlertTriangle {...iconProps} className={`${styles.icon} ${styles.warning}`} />
            case 'trend':
                return <TrendingUp {...iconProps} className={`${styles.icon} ${styles.trend}`} />
            case 'archive':
                return <Archive {...iconProps} className={`${styles.icon} ${styles.archive}`} />
            case 'info':
            default:
                return <Lightbulb {...iconProps} className={`${styles.icon} ${styles.info}`} />
        }
    }

    return (
        <div className={styles.container}>
            <div className={styles.header}>
                <h3 className={styles.title}>{title}</h3>
            </div>

            <div className={styles.list}>
                {insights.map((insight, idx) => (
                    <div key={idx} className={styles.item}>
                        {getIcon(insight.type)}
                        <span className={styles.text}>{insight.text}</span>
                    </div>
                ))}
            </div>
        </div>
    )
}
