'use client'

import { Bell, X, Check, Loader2 } from 'lucide-react'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Displays slow query alerts with summary statistics
 */
export default function AlertPanel({
    alertSummary,
    loading,
    onClose,
    onAcknowledgeAlert,
    onAcknowledgeAll,
    getSeverityColor,
}) {
    return (
        <div className={styles.alertPanel}>
            <div className={styles.alertHeader}>
                <Bell size={16} />
                <span>Slow Query Alerts</span>
                {alertSummary?.unacknowledgedCount > 0 && (
                    <button onClick={onAcknowledgeAll} className={styles.ackAllButton}>
                        Acknowledge All
                    </button>
                )}
                <button onClick={onClose} className={styles.closeButton}>
                    <X size={14} />
                </button>
            </div>
            {loading ? (
                <div className={styles.loadingIndicator}>
                    <Loader2 size={20} className={styles.spinner} />
                    <span>Loading alerts...</span>
                </div>
            ) : alertSummary ? (
                <>
                    <div className={styles.alertStats}>
                        <div className={styles.alertStat}>
                            <span className={styles.alertStatValue}>{alertSummary.totalAlerts}</span>
                            <span className={styles.alertStatLabel}>Total</span>
                        </div>
                        <div className={styles.alertStat}>
                            <span className={styles.alertStatValue} style={{ color: 'var(--color-danger)' }}>
                                {alertSummary.criticalAlerts}
                            </span>
                            <span className={styles.alertStatLabel}>Critical</span>
                        </div>
                        <div className={styles.alertStat}>
                            <span className={styles.alertStatValue} style={{ color: 'var(--color-warning)' }}>
                                {alertSummary.warningAlerts}
                            </span>
                            <span className={styles.alertStatLabel}>Warning</span>
                        </div>
                        <div className={styles.alertStat}>
                            <span className={styles.alertStatValue}>{alertSummary.unacknowledgedCount}</span>
                            <span className={styles.alertStatLabel}>Unread</span>
                        </div>
                    </div>
                    <div className={styles.alertList}>
                        {alertSummary.recentAlerts?.map((alert) => (
                            <div key={alert.id} className={styles.alertItem}>
                                <div className={styles.alertItemHeader}>
                                    <span
                                        className={styles.alertSeverityBadge}
                                        style={{ backgroundColor: getSeverityColor(alert.severity) }}
                                    >
                                        {alert.severity}
                                    </span>
                                    <span className={styles.alertTitle}>{alert.title}</span>
                                </div>
                                <div className={styles.alertMessage}>
                                    {alert.message?.substring(0, 150)}...
                                </div>
                                {!alert.acknowledged && (
                                    <button
                                        onClick={() => onAcknowledgeAlert(alert.id)}
                                        className={styles.ackButton}
                                    >
                                        <Check size={12} />
                                        Acknowledge
                                    </button>
                                )}
                            </div>
                        ))}
                    </div>
                </>
            ) : (
                <div className={styles.emptyWidget}>No alerts found</div>
            )}
        </div>
    )
}
