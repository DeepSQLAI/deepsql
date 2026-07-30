'use client'

import { useState } from 'react'
import { X, GitCompare, Loader2 } from 'lucide-react'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Modal for comparing two slow query analyses
 */
export default function CompareModal({
    history,
    onClose,
    onCompare,
    comparisonResult,
    loading,
    getTrendColor,
}) {
    const [period1, setPeriod1] = useState(null)
    const [period2, setPeriod2] = useState(null)

    const handleCompare = () => {
        if (period1 && period2) {
            onCompare(period1, period2)
        }
    }

    return (
        <div className={styles.uploadModal}>
            <div className={styles.uploadModalContent} style={{ maxWidth: '800px' }}>
                <div className={styles.uploadModalHeader}>
                    <h3>Compare Analyses</h3>
                    <button
                        onClick={() => {
                            onClose()
                        }}
                        className={styles.closeModalButton}
                    >
                        <X size={20} />
                    </button>
                </div>
                <div className={styles.uploadModalBody}>
                    <div className={styles.compareSelectors}>
                        <div className={styles.formGroup}>
                            <label>Period 1 (Earlier)</label>
                            <select
                                value={period1 || ''}
                                onChange={(e) => setPeriod1(e.target.value)}
                                className={styles.dbTypeSelect}
                            >
                                <option value="">Select analysis...</option>
                                {history.map((h) => (
                                    <option key={h.id} value={h.id}>
                                        {new Date(h.timestamp).toLocaleString()} - {h.totalSlowQueries} queries
                                    </option>
                                ))}
                            </select>
                        </div>
                        <div className={styles.formGroup}>
                            <label>Period 2 (Later)</label>
                            <select
                                value={period2 || ''}
                                onChange={(e) => setPeriod2(e.target.value)}
                                className={styles.dbTypeSelect}
                            >
                                <option value="">Select analysis...</option>
                                {history.map((h) => (
                                    <option key={h.id} value={h.id}>
                                        {new Date(h.timestamp).toLocaleString()} - {h.totalSlowQueries} queries
                                    </option>
                                ))}
                            </select>
                        </div>
                    </div>
                    <button
                        onClick={handleCompare}
                        disabled={!period1 || !period2 || loading}
                        className={styles.compareButton}
                    >
                        {loading ? (
                            <Loader2 size={14} className={styles.spinner} />
                        ) : (
                            <GitCompare size={14} />
                        )}
                        Compare
                    </button>

                    {comparisonResult && (
                        <div className={styles.comparisonResults}>
                            <h4>Comparison Results</h4>
                            <div className={styles.comparisonSummary}>
                                <div className={styles.comparisonCard}>
                                    <span className={styles.comparisonLabel}>Query Count Change</span>
                                    <span
                                        className={`${styles.comparisonValue} ${
                                            comparisonResult.summary?.changes?.slowQueryDiff < 0
                                                ? styles.positive
                                                : styles.negative
                                        }`}
                                    >
                                        {comparisonResult.summary?.changes?.slowQueryDiff > 0 ? '+' : ''}
                                        {comparisonResult.summary?.changes?.slowQueryDiff}
                                    </span>
                                </div>
                                <div className={styles.comparisonCard}>
                                    <span className={styles.comparisonLabel}>DB Time Change</span>
                                    <span
                                        className={`${styles.comparisonValue} ${
                                            comparisonResult.summary?.changes?.databaseTimeDiff < 0
                                                ? styles.positive
                                                : styles.negative
                                        }`}
                                    >
                                        {comparisonResult.summary?.changes?.databaseTimeDiff > 0 ? '+' : ''}
                                        {(comparisonResult.summary?.changes?.databaseTimeDiff / 1000)?.toFixed(2)}s
                                    </span>
                                </div>
                                <div className={styles.comparisonCard}>
                                    <span className={styles.comparisonLabel}>Trend</span>
                                    <span
                                        className={styles.comparisonValue}
                                        style={{
                                            color: getTrendColor(
                                                comparisonResult.summary?.changes?.trend === 'IMPROVING'
                                                    ? 'IMPROVING'
                                                    : 'DEGRADING'
                                            ),
                                        }}
                                    >
                                        {comparisonResult.summary?.changes?.trend}
                                    </span>
                                </div>
                            </div>
                            {comparisonResult.queryComparisons?.length > 0 && (
                                <div className={styles.queryComparisons}>
                                    <h5>Query Changes</h5>
                                    {comparisonResult.queryComparisons.slice(0, 5).map((qc, idx) => (
                                        <div key={idx} className={styles.queryCompareItem}>
                                            <code>{qc.queryPreview}</code>
                                            <div className={styles.qcMetrics}>
                                                <span>Before: {qc.period1AvgMs?.toFixed(1)}ms</span>
                                                <span>After: {qc.period2AvgMs?.toFixed(1)}ms</span>
                                                <span
                                                    style={{
                                                        color: qc.avgTimeDiff < 0 ? 'var(--color-dark-grey)' : 'var(--color-danger)',
                                                    }}
                                                >
                                                    {qc.avgTimeDiff > 0 ? '+' : ''}
                                                    {qc.avgTimeDiff?.toFixed(1)}ms
                                                </span>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>
                    )}
                </div>
            </div>
        </div>
    )
}
