'use client'

import { useState } from 'react'
import { AlertTriangle, Check, CheckCircle, ChevronDown, ChevronUp } from 'lucide-react'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Displays performance regressions detected in slow queries
 */
export default function RegressionPanel({
    regressions,
    onAcknowledge,
    onResolve,
    getSeverityColor,
}) {
    const [expanded, setExpanded] = useState(true)

    if (!regressions || regressions.length === 0) {
        return null
    }

    return (
        <div className={styles.regressionPanel}>
            <div className={styles.regressionHeader} onClick={() => setExpanded(!expanded)}>
                <div className={styles.regressionTitleSection}>
                    <AlertTriangle size={16} className={styles.regressionIcon} />
                    <span className={styles.regressionTitle}>
                        Performance Regressions Detected ({regressions.length})
                    </span>
                </div>
                <button className={styles.regressionToggle}>
                    {expanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                </button>
            </div>
            {expanded && (
                <div className={styles.regressionList}>
                    {regressions.map((regression) => (
                        <div key={regression.id} className={styles.regressionItem}>
                            <div className={styles.regressionSeverity}>
                                <span
                                    className={styles.severityBadge}
                                    style={{ backgroundColor: getSeverityColor(regression.severity) }}
                                >
                                    {regression.severity}
                                </span>
                                <span className={styles.slowdownPercent}>
                                    +{regression.slowdownPercent?.toFixed(0)}% slower
                                </span>
                            </div>
                            <div className={styles.regressionQuery}>
                                <code className={styles.queryCode}>
                                    {regression.normalizedQuery?.length > 100
                                        ? regression.normalizedQuery.substring(0, 100) + '...'
                                        : regression.normalizedQuery}
                                </code>
                            </div>
                            <div className={styles.regressionDetails}>
                                <span className={styles.regressionMetric}>
                                    Baseline: {regression.baselineAvgMs?.toFixed(1)}ms
                                </span>
                                <span className={styles.regressionMetric}>
                                    Current: {regression.currentAvgMs?.toFixed(1)}ms
                                </span>
                                <span className={styles.regressionMetric}>
                                    {regression.slowdownFactor?.toFixed(1)}x slower
                                </span>
                            </div>
                            <div className={styles.regressionActions}>
                                <button
                                    onClick={() => onAcknowledge(regression.id)}
                                    className={styles.acknowledgeButton}
                                    title="Acknowledge - I've seen this"
                                >
                                    <Check size={12} />
                                    <span>Acknowledge</span>
                                </button>
                                <button
                                    onClick={() => onResolve(regression.id)}
                                    className={styles.resolveButton}
                                    title="Mark as resolved"
                                >
                                    <CheckCircle size={12} />
                                    <span>Resolve</span>
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    )
}
