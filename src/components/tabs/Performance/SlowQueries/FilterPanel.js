'use client'

import { Filter } from 'lucide-react'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Filter panel for slow query analysis parameters
 */
export default function FilterPanel({
    timeRange,
    setTimeRange,
    thresholdMs,
    setThresholdMs,
    limit,
    setLimit,
    onApply,
}) {
    return (
        <div className={styles.filterPanel}>
            <div className={styles.filterGroup}>
                <label>Time Range</label>
                <select
                    value={timeRange}
                    onChange={(e) => setTimeRange(e.target.value)}
                >
                    <option value="LAST_HOUR">Last Hour</option>
                    <option value="LAST_24_HOURS">Last 24 Hours</option>
                    <option value="LAST_7_DAYS">Last 7 Days</option>
                    <option value="LAST_30_DAYS">Last 30 Days</option>
                    <option value="ALL_TIME">All Time</option>
                </select>
            </div>
            <div className={styles.filterGroup}>
                <label>Threshold (ms)</label>
                <input
                    type="number"
                    value={thresholdMs}
                    onChange={(e) => setThresholdMs(Number(e.target.value))}
                    min="1"
                />
            </div>
            <div className={styles.filterGroup}>
                <label>Limit</label>
                <input
                    type="number"
                    value={limit}
                    onChange={(e) => setLimit(Number(e.target.value))}
                    min="1"
                    max="100"
                />
            </div>
            <button className={styles.applyButton} onClick={onApply} title="Apply Filters">
                <Filter size={14} />
            </button>
        </div>
    )
}
