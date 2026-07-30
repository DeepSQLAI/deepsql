'use client'

import { History, Clock, X } from 'lucide-react'
import { formatTimestamp } from './utils'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Displays analysis history with ability to load past analyses
 */
export default function HistoryPanel({
    history,
    onClose,
    onLoadHistory,
    onDeleteHistory,
    getHealthColor,
}) {
    if (!history || history.length === 0) {
        return null
    }

    return (
        <>
        <div className={styles.historyPanelOverlay} onClick={onClose} />
        <div className={styles.historyPanel}>
            <div className={styles.historyHeader}>
                <div className={styles.historyTitle}>
                    <History size={20} />
                    <h3>Analysis History</h3>
                    <span className={styles.historyCount}>{history.length} analyses</span>
                </div>
                <button
                    className={styles.closeHistoryButton}
                    onClick={onClose}
                >
                    <X size={20} />
                </button>
            </div>
            <div className={styles.historyList}>
                {history.map((item) => (
                    <div
                        key={item.id}
                        className={styles.historyItem}
                        onClick={() => onLoadHistory(item)}
                    >
                        <div className={styles.historyItemHeader}>
                            <div className={styles.historyItemMeta}>
                                <Clock size={14} />
                                <span className={styles.historyTimestamp}>
                                    {formatTimestamp(item.timestamp)}
                                </span>
                                {item.timeRange && (
                                    <span className={styles.timeRangeFlag}>
                                        {item.timeRange.replace('_', ' ')}
                                    </span>
                                )}
                            </div>
                            <div className={styles.historyItemActions}>
                                <div
                                    className={styles.historyHealth}
                                    style={{ color: getHealthColor(item.overallHealth) }}
                                >
                                    {item.overallHealth}
                                </div>
                                <button
                                    className={styles.deleteHistoryButton}
                                    onClick={(e) => {
                                        e.stopPropagation()
                                        onDeleteHistory(item.id)
                                    }}
                                    title="Delete from history"
                                >
                                    <X size={16} />
                                </button>
                            </div>
                        </div>
                        <div className={styles.historyStats}>
                            <span>{item.totalSlowQueries} slow queries</span>
                            {item.criticalCount > 0 && (
                                <span className={styles.criticalBadge}>
                                    {item.criticalCount} critical
                                </span>
                            )}
                            {item.highCount > 0 && (
                                <span className={styles.highBadge}>
                                    {item.highCount} high
                                </span>
                            )}
                        </div>
                    </div>
                ))}
            </div>
        </div>
        </>
    )
}
