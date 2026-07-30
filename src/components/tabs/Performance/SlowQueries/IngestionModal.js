'use client'

import ReactDOM from 'react-dom'
import { X, RefreshCw, CheckCircle, AlertCircle } from 'lucide-react'
import { getProviderSource, formatTimestamp, formatBytes } from './utils'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Modal for triggering and monitoring slow query log ingestion
 */
export default function IngestionModal({
    sourceConfig,
    selectedTimeRange,
    setSelectedTimeRange,
    ingesting,
    currentJob,
    ingestStatus,
    onClose,
    onStartIngestion,
    onCancelIngestion,
    showDetails = false, // Show additional details like last ingested time
}) {
    // Portal to body for proper z-index stacking
    return ReactDOM.createPortal(
        <div style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0, 0, 0, 0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 99999
        }}>
            <div style={{
                background: 'white',
                borderRadius: '12px',
                width: '100%',
                maxWidth: '500px',
                maxHeight: '90vh',
                overflowY: 'auto',
                boxShadow: '0 20px 60px rgba(0, 0, 0, 0.2)'
            }}>
                <div className={styles.uploadModalHeader}>
                    <h3>Ingest Slow Query Logs</h3>
                    <button
                        className={styles.closeModalButton}
                        onClick={onClose}
                    >
                        <X size={20} />
                    </button>
                </div>
                <div className={styles.ingestModalBody}>
                    {/* Config Summary */}
                    <div className={styles.ingestConfigSummary}>
                        <div className={styles.ingestConfigRow}>
                            <span className={styles.ingestConfigLabel}>Source</span>
                            <span className={styles.ingestConfigValue}>
                                {sourceConfig.providerType === 'CLOUDWATCH' ? 'AWS CloudWatch' : 'AWS S3'}
                            </span>
                        </div>
                        <div className={styles.ingestConfigRow}>
                            <span className={styles.ingestConfigLabel}>
                                {sourceConfig.providerType === 'CLOUDWATCH' ? 'Log Group' : 'Bucket'}
                            </span>
                            <span className={styles.ingestConfigValueMono}>
                                {getProviderSource(sourceConfig)}
                            </span>
                        </div>
                        <div className={styles.ingestConfigRow}>
                            <span className={styles.ingestConfigLabel}>Region</span>
                            <span className={styles.ingestConfigValue}>{sourceConfig.s3Region}</span>
                        </div>
                        {showDetails && (
                            <>
                                <div className={styles.ingestConfigRow}>
                                    <span className={styles.ingestConfigLabel}>Last Ingested</span>
                                    <span className={styles.ingestConfigValue}>
                                        {sourceConfig.lastProcessedAt
                                            ? new Date(sourceConfig.lastProcessedAt).toLocaleString()
                                            : 'Never'}
                                    </span>
                                </div>
                                <div className={styles.ingestConfigRow}>
                                    <span className={styles.ingestConfigLabel}>Max Events</span>
                                    <span className={styles.ingestConfigValue}>10,000</span>
                                </div>
                            </>
                        )}
                    </div>

                    {/* Time Range Selection */}
                    <div className={styles.timeRangeSection}>
                        <label className={styles.timeRangeLabel}>Time Range</label>
                        <div className={styles.timeRangeOptions}>
                            {sourceConfig.lastProcessedAt && (
                                <label className={styles.timeRangeOption}>
                                    <input
                                        type="radio"
                                        name="timeRange"
                                        value="SINCE_LAST"
                                        checked={selectedTimeRange === 'SINCE_LAST'}
                                        onChange={(e) => setSelectedTimeRange(e.target.value)}
                                    />
                                    <div className={styles.timeRangeOptionContent}>
                                        <span className={styles.timeRangeOptionTitle}>Since Last Ingestion</span>
                                        {showDetails && (
                                            <span className={styles.timeRangeOptionDesc}>
                                                Fetch new logs since {formatTimestamp(sourceConfig.lastProcessedAt)}
                                            </span>
                                        )}
                                    </div>
                                </label>
                            )}
                            <label className={styles.timeRangeOption}>
                                <input
                                    type="radio"
                                    name="timeRange"
                                    value="LAST_24_HOURS"
                                    checked={selectedTimeRange === 'LAST_24_HOURS'}
                                    onChange={(e) => setSelectedTimeRange(e.target.value)}
                                />
                                <div className={styles.timeRangeOptionContent}>
                                    <span className={styles.timeRangeOptionTitle}>Last 24 Hours</span>
                                    {showDetails && (
                                        <span className={styles.timeRangeOptionDesc}>Fetch logs from the past 24 hours</span>
                                    )}
                                </div>
                            </label>
                            <label className={styles.timeRangeOption}>
                                <input
                                    type="radio"
                                    name="timeRange"
                                    value="LAST_7_DAYS"
                                    checked={selectedTimeRange === 'LAST_7_DAYS'}
                                    onChange={(e) => setSelectedTimeRange(e.target.value)}
                                />
                                <div className={styles.timeRangeOptionContent}>
                                    <span className={styles.timeRangeOptionTitle}>Last 7 Days</span>
                                    {showDetails && (
                                        <span className={styles.timeRangeOptionDesc}>Fetch logs from the past week</span>
                                    )}
                                </div>
                            </label>
                            {showDetails && (
                                <label className={styles.timeRangeOption}>
                                    <input
                                        type="radio"
                                        name="timeRange"
                                        value="LAST_30_DAYS"
                                        checked={selectedTimeRange === 'LAST_30_DAYS'}
                                        onChange={(e) => setSelectedTimeRange(e.target.value)}
                                    />
                                    <div className={styles.timeRangeOptionContent}>
                                        <span className={styles.timeRangeOptionTitle}>Last 30 Days</span>
                                        <span className={styles.timeRangeOptionDesc}>Fetch logs from the past month</span>
                                    </div>
                                </label>
                            )}
                            <label className={styles.timeRangeOption}>
                                <input
                                    type="radio"
                                    name="timeRange"
                                    value="ALL"
                                    checked={selectedTimeRange === 'ALL'}
                                    onChange={(e) => setSelectedTimeRange(e.target.value)}
                                />
                                <div className={styles.timeRangeOptionContent}>
                                    <span className={styles.timeRangeOptionTitle}>All Available</span>
                                    {showDetails && (
                                        <span className={styles.timeRangeOptionDesc}>Fetch all logs (up to 10,000 events)</span>
                                    )}
                                </div>
                            </label>
                        </div>
                    </div>

                    {/* Progress Section - shown when job is running */}
                    {ingesting && currentJob && (
                        <div className={styles.progressSection}>
                            <div className={styles.progressHeader}>
                                <span className={styles.progressStep}>{currentJob.currentStep || 'Processing...'}</span>
                                <span className={styles.progressPercent}>{currentJob.progressPct || 0}%</span>
                            </div>
                            <div className={styles.progressBarContainer}>
                                <div
                                    className={styles.progressBar}
                                    style={{ width: `${currentJob.progressPct || 0}%` }}
                                />
                            </div>
                            <div className={styles.progressStats}>
                                <div className={styles.progressStatItem}>
                                    <span className={styles.progressStatLabel}>Log Data</span>
                                    <span className={styles.progressStatValue}>
                                        {formatBytes(currentJob.bytesProcessed || 0)}
                                    </span>
                                </div>
                                <div className={styles.progressStatItem}>
                                    <span className={styles.progressStatLabel}>Slow Queries Found</span>
                                    <span className={styles.progressStatValue} style={{ color: currentJob.slowQueriesFound > 0 ? 'var(--color-dark-grey)' : 'inherit' }}>
                                        {(currentJob.slowQueriesFound || 0).toLocaleString()}
                                    </span>
                                </div>
                                {currentJob.timeRange && (
                                    <div className={styles.progressStatItem}>
                                        <span className={styles.progressStatLabel}>Time Range</span>
                                        <span className={styles.progressStatValue}>
                                            {currentJob.timeRange === 'ALL' ? 'All Available' :
                                             currentJob.timeRange === 'SINCE_LAST' ? 'Since Last' :
                                             currentJob.timeRange.replace('LAST_', 'Last ').replace('_', ' ')}
                                        </span>
                                    </div>
                                )}
                            </div>
                        </div>
                    )}

                    {/* Result message */}
                    {ingestStatus && !ingesting && (
                        <div className={`${styles.ingestResultMessage} ${ingestStatus.success ? styles.success : styles.error}`}>
                            {ingestStatus.success ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
                            <span>{ingestStatus.message}</span>
                        </div>
                    )}

                    {/* Actions */}
                    <div className={styles.ingestModalActions}>
                        {!ingesting ? (
                            <>
                                <button
                                    className={styles.cancelButton}
                                    onClick={onClose}
                                >
                                    {ingestStatus ? 'Close' : 'Cancel'}
                                </button>
                                {!ingestStatus && (
                                    <button
                                        type="button"
                                        className={styles.ingestConfirmButton}
                                        onClick={() => onStartIngestion(selectedTimeRange)}
                                    >
                                        <RefreshCw size={16} />
                                        <span>Start Ingestion</span>
                                    </button>
                                )}
                            </>
                        ) : (
                            <button
                                className={styles.cancelIngestionButton}
                                onClick={onCancelIngestion}
                            >
                                <X size={16} />
                                <span>Cancel Ingestion</span>
                            </button>
                        )}
                    </div>
                </div>
            </div>
        </div>,
        document.body
    )
}
