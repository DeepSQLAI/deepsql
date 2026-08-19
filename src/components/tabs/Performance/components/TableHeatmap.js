'use client'

import { HelpCircle } from 'lucide-react'
import { HelpTooltip } from '@/components/tabs/Brain/components/HelpTooltip'
import styles from './TableHeatmap.module.css'

const USAGE_SCORE_DETAILS = {
    score: {
        title: 'Usage Score Breakdown (0–100)',
        categories: [
            { name: 'Scan activity', metrics: '0–40 points (more scans = higher score)' },
            { name: 'Row activity', metrics: '0–30 points (more rows read/write = higher score)' },
            { name: 'Slow queries', metrics: '0–30 points (slow-query mentions)' },
            { name: 'Cap', metrics: 'Total score is capped at 100' },
        ],
    },
}

/**
 * Help content for the Table Activity section
 */
const TABLE_ACTIVITY_HELP = {
    title: 'Table Activity',
    description: 'Shows which tables are most actively accessed based on query patterns and scan frequency.',
    impact: 'Scores are a 0–100 usage index, not a share of total queries. 100% means the table hit the maximum usage score.',
    recommendation: 'Focus optimization efforts on tables with 70%+ activity. Consider archiving or partitioning tables with consistently high read/write loads.',
    details: USAGE_SCORE_DETAILS,
}

/**
 * Generate tooltip content for a specific table based on its activity score
 */
const getTableTooltipContent = (tableName, usageScore) => {
    let activityLevel, description, recommendation

    if (usageScore >= 90) {
        activityLevel = 'Critical'
        description = 'This table is under extremely heavy load and is accessed in the vast majority of queries.'
        recommendation = 'Prioritize indexing, consider read replicas, implement caching, or evaluate partitioning strategies.'
    } else if (usageScore >= 70) {
        activityLevel = 'High'
        description = 'This table is frequently accessed and is a hot spot in your workload.'
        recommendation = 'Ensure indexes cover common query patterns. Consider query optimization or caching for repeated reads.'
    } else if (usageScore >= 50) {
        activityLevel = 'Moderate'
        description = 'This table sees regular activity but is not a primary bottleneck.'
        recommendation = 'Review slow queries involving this table. Standard indexing practices should suffice.'
    } else if (usageScore >= 30) {
        activityLevel = 'Low'
        description = 'This table has relatively light usage compared to others.'
        recommendation = 'May be a good candidate for archiving old data or consolidation if rarely updated.'
    } else {
        activityLevel = 'Minimal'
        description = 'This table is rarely accessed in the analyzed query patterns.'
        recommendation = 'Evaluate if this table is still needed. Consider archiving or removing if obsolete.'
    }

    return {
        title: tableName,
        description: `Activity Score: ${usageScore}% (${activityLevel}). 100% means max usage score, not % of total queries.`,
        impact: description,
        recommendation,
        details: USAGE_SCORE_DETAILS,
    }
}

/**
 * Horizontal bar chart showing table usage/activity.
 * Bar opacity reflects usage score (0-100).
 *
 * @param {Array<{tableName: string, usageScore: number}>} data - Table usage data
 * @param {string} caption - Caption text below the chart
 */
export default function TableHeatmap({ data = [], caption = 'Based on scan frequency over last 24 hours' }) {
    if (!data || data.length === 0) {
        return (
            <div className={styles.container}>
                <div className={styles.header}>
                    <h3 className={styles.title}>
                        Table Activity
                        <HelpTooltip content={TABLE_ACTIVITY_HELP}>
                            <HelpCircle size={12} className={styles.helpIcon} />
                        </HelpTooltip>
                    </h3>
                </div>
                <div className={styles.emptyState}>
                    No table usage data available
                </div>
            </div>
        )
    }

    const maxScore = Math.max(...data.map(d => d.usageScore), 1)

    return (
        <div className={styles.container}>
            <div className={styles.header}>
                <h3 className={styles.title}>
                    Table Activity
                    <HelpTooltip content={TABLE_ACTIVITY_HELP}>
                        <HelpCircle size={12} className={styles.helpIcon} />
                    </HelpTooltip>
                </h3>
            </div>

            <div className={styles.bars}>
                {data.slice(0, 10).map((item, idx) => {
                    const widthPercent = Math.max((item.usageScore / maxScore) * 100, 8)
                    const opacity = 0.3 + (item.usageScore / 100) * 0.7
                    const displayName = item.tableName || ''
                    const tooltipContent = getTableTooltipContent(displayName, item.usageScore)

                    return (
                        <HelpTooltip key={idx} content={tooltipContent} block>
                            <div className={styles.barRow}>
                                <span className={styles.tableName}>
                                    {displayName}
                                </span>
                                <div className={styles.barTrack}>
                                    <div
                                        className={styles.barFill}
                                        style={{
                                            width: `${widthPercent}%`,
                                            opacity,
                                        }}
                                    />
                                </div>
                                <span className={styles.percentage}>{item.usageScore}%</span>
                            </div>
                        </HelpTooltip>
                    )
                })}
            </div>

            <div className={styles.caption}>{caption}</div>
        </div>
    )
}
