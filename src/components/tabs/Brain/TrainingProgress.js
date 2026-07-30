'use client'

import { Loader, CheckCircle, XCircle, Clock, AlertCircle } from 'lucide-react'
import styles from './TrainingProgress.module.css'

/**
 * Prominent training progress indicator
 * Shows current status, progress, and estimated time
 */
export function TrainingProgress({ status }) {
    if (!status) {
        return null
    }

    const { status: statusType, processedTables, totalTables, currentTable, message, error, progressPercent } = status

    // Don't show if not running, pending, or cancelling
    if (statusType !== 'RUNNING' && statusType !== 'PENDING' && statusType !== 'CANCELLING') {
        return null
    }

    const progress = progressPercent || 0
    const tablesProcessed = processedTables || 0
    const tablesTotal = totalTables || 0

    return (
        <div className={styles.progressContainer}>
            <div className={styles.progressHeader}>
                <div className={styles.statusIcon}>
                    <Loader className={styles.spinner} size={20} />
                </div>
                <div className={styles.statusText}>
                    <div className={styles.statusLabel}>
                        {statusType === 'PENDING' && 'Queued for Training'}
                        {statusType === 'RUNNING' && 'Training in Progress'}
                        {statusType === 'CANCELLING' && 'Cancelling Training...'}
                    </div>
                    <div className={styles.statusDetail}>
                        {statusType === 'CANCELLING' ? (
                            message || 'Stopping training process...'
                        ) : tablesTotal > 0 ? (
                            `Processing table ${tablesProcessed} of ${tablesTotal}`
                        ) : (
                            message || 'Preparing...'
                        )}
                    </div>
                </div>
            </div>

            {statusType === 'RUNNING' && tablesTotal > 0 && (
                <>
                    <div className={styles.progressBar}>
                        <div
                            className={styles.progressFill}
                            style={{ width: `${progress}%` }}
                        />
                    </div>
                    <div className={styles.progressStats}>
                        <span className={styles.progressPercent}>{progress.toFixed(1)}%</span>
                        <span className={styles.progressTables}>
                            {tablesProcessed}/{tablesTotal} tables
                        </span>
                    </div>
                </>
            )}

            {currentTable && (
                <div className={styles.currentTable}>
                    <span className={styles.currentTableLabel}>Currently processing:</span>
                    <span className={styles.currentTableName}>{currentTable}</span>
                </div>
            )}

            {statusType === 'PENDING' && (
                <div className={styles.queueInfo}>
                    <Clock size={14} />
                    <span>Waiting for available training slot...</span>
                </div>
            )}
        </div>
    )
}

/**
 * Compact status badge for header
 */
export function TrainingStatusBadge({ status }) {
    if (!status) {
        return null
    }

    const getStatusInfo = () => {
        switch (status.status) {
            case 'RUNNING':
                return {
                    icon: <Loader className={styles.spinner} size={14} />,
                    label: `Training ${status.processedTables || 0}/${status.totalTables || 0}`,
                    className: styles.statusRunning
                }
            case 'PENDING':
                return {
                    icon: <Clock size={14} />,
                    label: 'Queued',
                    className: styles.statusPending
                }
            case 'CANCELLING':
                return {
                    icon: <Loader className={styles.spinner} size={14} />,
                    label: 'Cancelling...',
                    className: styles.statusCancelled
                }
            case 'COMPLETED':
                return {
                    icon: <CheckCircle size={14} />,
                    label: 'Completed',
                    className: styles.statusCompleted
                }
            case 'FAILED':
                return {
                    icon: <XCircle size={14} />,
                    label: 'Failed',
                    className: styles.statusFailed
                }
            case 'CANCELLED':
                return {
                    icon: <XCircle size={14} />,
                    label: 'Cancelled',
                    className: styles.statusCancelled
                }
            case 'REJECTED':
                return {
                    icon: <AlertCircle size={14} />,
                    label: 'Queue Full',
                    className: styles.statusRejected
                }
            default:
                return null
        }
    }

    const statusInfo = getStatusInfo()
    if (!statusInfo) return null

    return (
        <div className={`${styles.statusBadge} ${statusInfo.className}`}>
            {statusInfo.icon}
            <span>{statusInfo.label}</span>
            {status.progressPercent > 0 && status.status === 'RUNNING' && (
                <span className={styles.badgePercent}>({status.progressPercent.toFixed(0)}%)</span>
            )}
        </div>
    )
}
