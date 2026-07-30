'use client'

import { X, Info } from 'lucide-react'
import { getProviderLabel } from './utils'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Modal for configuring slow log source (S3, CloudWatch, Azure, GCP, Datadog, Elasticsearch)
 */
export default function SourceConfigModal({
    sourceConfig,
    setSourceConfig,
    onClose,
    onSave,
    onIngestNow,
    onShowIamPolicy,
    ingestStatus,
}) {
    return (
        <div className={styles.uploadModal}>
            <div className={styles.uploadModalContent}>
                <div className={styles.uploadModalHeader}>
                    <h3>Slow Log Source</h3>
                    <button
                        className={styles.closeModalButton}
                        onClick={onClose}
                    >
                        <X size={20} />
                    </button>
                </div>
                <div className={styles.uploadModalBody}>
                    {/* Enable/Provider Selection */}
                    <div className={styles.formRow}>
                        <div className={styles.formGroup}>
                            <label>Enable Auto Ingestion</label>
                            <select
                                value={sourceConfig.enabled ? 'yes' : 'no'}
                                onChange={(e) => setSourceConfig(prev => ({
                                    ...prev,
                                    enabled: e.target.value === 'yes'
                                }))}
                                className={styles.dbTypeSelect}
                            >
                                <option value="no">Disabled</option>
                                <option value="yes">Enabled</option>
                            </select>
                        </div>
                        <div className={styles.formGroup}>
                            <label>Provider</label>
                            <select
                                value={sourceConfig.providerType}
                                onChange={(e) => setSourceConfig(prev => ({
                                    ...prev,
                                    providerType: e.target.value,
                                    refreshFrequencyMinutes: e.target.value === 'CLOUDWATCH'
                                        ? Math.max(prev.refreshFrequencyMinutes || 1440, 1440)
                                        : prev.refreshFrequencyMinutes || 60
                                }))}
                                className={styles.dbTypeSelect}
                            >
                                <option value="S3">AWS S3</option>
                                <option value="CLOUDWATCH">AWS CloudWatch Logs</option>
                            </select>
                            <span className={styles.comingSoonNote}>Azure, GCP, Datadog coming soon</span>
                        </div>
                    </div>

                    {/* S3 Fields */}
                    {sourceConfig.providerType === 'S3' && (
                        <div className={styles.formRow}>
                            <div className={styles.formGroup}>
                                <label>S3 Bucket</label>
                                <input
                                    type="text"
                                    placeholder="my-bucket"
                                    value={sourceConfig.bucketName}
                                    onChange={(e) => setSourceConfig(prev => ({ ...prev, bucketName: e.target.value }))}
                                    className={styles.textInput}
                                />
                            </div>
                            <div className={styles.formGroup}>
                                <label>Object Key / Prefix</label>
                                <input
                                    type="text"
                                    placeholder="logs/slow.log"
                                    value={sourceConfig.objectPrefix}
                                    onChange={(e) => setSourceConfig(prev => ({ ...prev, objectPrefix: e.target.value }))}
                                    className={styles.textInput}
                                />
                            </div>
                        </div>
                    )}

                    {/* CloudWatch Fields */}
                    {sourceConfig.providerType === 'CLOUDWATCH' && (
                        <div className={styles.formRow}>
                            <div className={styles.formGroup}>
                                <label>Log Group Name</label>
                                <input
                                    type="text"
                                    placeholder="/aws/rds/instance/slowquery"
                                    value={sourceConfig.logGroupName}
                                    onChange={(e) => setSourceConfig(prev => ({ ...prev, logGroupName: e.target.value }))}
                                    className={styles.textInput}
                                />
                            </div>
                            <div className={styles.formGroup}>
                                <label>Log Stream Prefix (optional)</label>
                                <input
                                    type="text"
                                    placeholder="slowquery"
                                    value={sourceConfig.logStreamPrefix}
                                    onChange={(e) => setSourceConfig(prev => ({ ...prev, logStreamPrefix: e.target.value }))}
                                    className={styles.textInput}
                                />
                            </div>
                        </div>
                    )}

                    {/* AWS-specific fields (S3 or CloudWatch) */}
                    {(sourceConfig.providerType === 'S3' || sourceConfig.providerType === 'CLOUDWATCH') && (
                        <>
                            <div className={styles.formRow}>
                                <div className={styles.formGroup}>
                                    <label>AWS Region</label>
                                    <input
                                        type="text"
                                        placeholder="us-east-1"
                                        value={sourceConfig.s3Region}
                                        onChange={(e) => setSourceConfig(prev => ({ ...prev, s3Region: e.target.value }))}
                                        className={styles.textInput}
                                    />
                                </div>
                                <div className={styles.formGroup}>
                                    <label>Check Frequency (minutes)</label>
                                    <input
                                        type="number"
                                        min={sourceConfig.providerType === 'CLOUDWATCH' ? 1440 : 5}
                                        value={sourceConfig.refreshFrequencyMinutes}
                                        onChange={(e) => setSourceConfig(prev => ({
                                            ...prev,
                                            refreshFrequencyMinutes: Number(e.target.value)
                                        }))}
                                        className={styles.textInput}
                                    />
                                </div>
                            </div>

                            <div className={styles.formRow}>
                                <div className={styles.formGroup}>
                                    <label>AWS Access Key Id</label>
                                    <input
                                        type="password"
                                        placeholder="AKIA..."
                                        value={sourceConfig.accessKeyId}
                                        onChange={(e) => setSourceConfig(prev => ({ ...prev, accessKeyId: e.target.value }))}
                                        className={styles.textInput}
                                    />
                                </div>
                                <div className={styles.formGroup}>
                                    <label>AWS Secret Access Key</label>
                                    <input
                                        type="password"
                                        placeholder="********"
                                        value={sourceConfig.secretAccessKey}
                                        onChange={(e) => setSourceConfig(prev => ({ ...prev, secretAccessKey: e.target.value }))}
                                        className={styles.textInput}
                                    />
                                </div>
                            </div>

                            <div className={styles.formGroup}>
                                <label>AWS Session Token (optional)</label>
                                <input
                                    type="password"
                                    placeholder="(optional)"
                                    value={sourceConfig.sessionToken}
                                    onChange={(e) => setSourceConfig(prev => ({ ...prev, sessionToken: e.target.value }))}
                                    className={styles.textInput}
                                />
                            </div>
                        </>
                    )}

                    {/* Actions */}
                    <div className={styles.modalActions}>
                        <button className={styles.s3AnalyzeButton} onClick={onSave}>
                            Save Config
                        </button>
                        <button className={styles.uploadButton} onClick={onIngestNow}>
                            Ingest Now
                        </button>
                        {(sourceConfig.providerType === 'S3' || sourceConfig.providerType === 'CLOUDWATCH') && (
                            <button
                                type="button"
                                className={styles.iamPolicyButton}
                                onClick={onShowIamPolicy}
                            >
                                <Info size={14} />
                                <span>View IAM Policy</span>
                            </button>
                        )}
                    </div>

                    {/* Status Message */}
                    {ingestStatus && (
                        <div className={styles.uploadingIndicator}>
                            <span style={{ color: ingestStatus.success ? 'var(--color-success)' : 'var(--color-error)' }}>
                                {ingestStatus.message}
                            </span>
                        </div>
                    )}
                </div>
            </div>
        </div>
    )
}
