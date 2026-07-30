'use client'

import { useState, useEffect, useCallback } from 'react'
import {
    Cloud, Upload, Play, CheckCircle2, AlertCircle, Loader2,
    Clock, RefreshCw, History, ChevronDown, ChevronRight, Eye, EyeOff
} from 'lucide-react'
import { slowLogSourceAPI } from '@/lib/api/client'
import styles from './SlowQueryIngestionTab.module.css'

const PROVIDERS = [
    { key: 'CLOUDWATCH', label: 'AWS CloudWatch', icon: '☁️' },
    { key: 'S3', label: 'AWS S3', icon: '📦' },
]

const TIME_RANGES = [
    { key: 'SINCE_LAST', label: 'Since last ingestion' },
    { key: 'LAST_24_HOURS', label: 'Last 24 hours' },
    { key: 'LAST_7_DAYS', label: 'Last 7 days' },
    { key: 'LAST_30_DAYS', label: 'Last 30 days' },
]

const EMPTY_FORM = {
    providerType: 'CLOUDWATCH',
    // CloudWatch
    logGroupName: '',
    logStreamPrefix: '',
    // S3
    bucketName: '',
    objectPrefix: '',
    s3Region: 'us-east-1',
    // AWS credentials
    accessKeyId: '',
    secretAccessKey: '',
    // Azure
    azureConnectionString: '',
    azureContainerName: '',
    azureBlobPrefix: '',
    // GCP
    gcpProjectId: '',
    gcpLogFilter: '',
    gcpServiceAccountJson: '',
    // Datadog
    datadogApiKey: '',
    datadogAppKey: '',
    datadogSite: 'datadoghq.com',
    datadogQuery: '',
    // Elasticsearch
    elasticsearchHost: '',
    elasticsearchIndexPattern: '',
    elasticsearchApiKey: '',
    // Schedule
    refreshFrequencyMinutes: 60,
    autoScheduleEnabled: false,
}

function ProviderFields({ provider, form, onChange }) {
    const [showSecrets, setShowSecrets] = useState({})
    const toggle = (field) => setShowSecrets(prev => ({ ...prev, [field]: !prev[field] }))

    const field = (label, name, opts = {}) => {
        const { placeholder, type = 'text', secret = false, wide = false } = opts
        return (
            <div className={wide ? styles.fieldWide : styles.field}>
                <label className={styles.fieldLabel}>{label}</label>
                <div className={secret ? styles.secretWrap : undefined}>
                    <input
                        className={styles.fieldInput}
                        type={secret && !showSecrets[name] ? 'password' : type}
                        value={form[name] || ''}
                        onChange={(e) => onChange(name, e.target.value)}
                        placeholder={placeholder || ''}
                    />
                    {secret && (
                        <button
                            type="button"
                            className={styles.secretToggle}
                            onClick={() => toggle(name)}
                            title={showSecrets[name] ? 'Hide' : 'Show'}
                        >
                            {showSecrets[name] ? <EyeOff size={14} /> : <Eye size={14} />}
                        </button>
                    )}
                </div>
            </div>
        )
    }

    switch (provider) {
        case 'CLOUDWATCH':
            return (
                <div className={styles.fieldGrid}>
                    {field('Log Group Name', 'logGroupName', { placeholder: '/aws/rds/cluster/my-db/slowquery' })}
                    {field('Log Stream Prefix', 'logStreamPrefix', { placeholder: 'my-db-instance (optional)' })}
                    {field('AWS Access Key ID', 'accessKeyId', { secret: true })}
                    {field('AWS Secret Access Key', 'secretAccessKey', { secret: true })}
                </div>
            )
        case 'S3':
            return (
                <div className={styles.fieldGrid}>
                    {field('Bucket Name', 'bucketName', { placeholder: 'my-slow-log-bucket' })}
                    {field('Object Prefix', 'objectPrefix', { placeholder: 'logs/slow-queries/' })}
                    {field('Region', 's3Region', { placeholder: 'us-east-1' })}
                    {field('AWS Access Key ID', 'accessKeyId', { secret: true })}
                    {field('AWS Secret Access Key', 'secretAccessKey', { secret: true })}
                </div>
            )
        case 'AZURE_BLOB':
            return (
                <div className={styles.fieldGrid}>
                    {field('Connection String', 'azureConnectionString', { secret: true, wide: true })}
                    {field('Container Name', 'azureContainerName', { placeholder: 'slow-logs' })}
                    {field('Blob Prefix', 'azureBlobPrefix', { placeholder: 'mysql/slowquery/' })}
                </div>
            )
        case 'GCP_LOGGING':
            return (
                <div className={styles.fieldGrid}>
                    {field('GCP Project ID', 'gcpProjectId', { placeholder: 'my-gcp-project' })}
                    {field('Log Filter', 'gcpLogFilter', { placeholder: 'resource.type="cloudsql_database"', wide: true })}
                    {field('Service Account JSON', 'gcpServiceAccountJson', { secret: true, wide: true })}
                </div>
            )
        case 'DATADOG':
            return (
                <div className={styles.fieldGrid}>
                    {field('API Key', 'datadogApiKey', { secret: true })}
                    {field('App Key', 'datadogAppKey', { secret: true })}
                    {field('Site', 'datadogSite', { placeholder: 'datadoghq.com' })}
                    {field('Query Filter', 'datadogQuery', { placeholder: 'service:mysql @duration:>1s' })}
                </div>
            )
        case 'ELASTICSEARCH':
            return (
                <div className={styles.fieldGrid}>
                    {field('Host', 'elasticsearchHost', { placeholder: 'https://my-cluster.es.io:9243' })}
                    {field('Index Pattern', 'elasticsearchIndexPattern', { placeholder: 'slowlogs-*' })}
                    {field('API Key', 'elasticsearchApiKey', { secret: true })}
                </div>
            )
        default:
            return null
    }
}

function JobHistoryRow({ job }) {
    const [expanded, setExpanded] = useState(false)
    const statusColor = {
        COMPLETED: styles.statusSuccess,
        FAILED: styles.statusError,
        RUNNING: styles.statusRunning,
        CANCELLED: styles.statusMuted,
    }[job.status] || styles.statusMuted

    return (
        <div className={styles.historyItem}>
            <button
                type="button"
                className={styles.historyRowBtn}
                onClick={() => setExpanded(!expanded)}
            >
                <span className={statusColor}>{job.status}</span>
                <span className={styles.historyMeta}>
                    {job.totalProcessed ?? 0} queries · {job.timeRange || '—'}
                </span>
                <span className={styles.historyDate}>
                    {job.startTime ? new Date(job.startTime).toLocaleString() : '—'}
                </span>
                {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            </button>
            {expanded && (
                <div className={styles.historyDetail}>
                    {job.errorMessage && <p className={styles.historyError}>{job.errorMessage}</p>}
                    {job.endTime && <p>Completed: {new Date(job.endTime).toLocaleString()}</p>}
                    {job.totalSkipped > 0 && <p>Skipped: {job.totalSkipped}</p>}
                </div>
            )}
        </div>
    )
}

export default function SlowQueryIngestionTab() {
    const connectionId = typeof window !== 'undefined' ? localStorage.getItem('selectedConnectionId') : null
    const [form, setForm] = useState({ ...EMPTY_FORM })
    const [saving, setSaving] = useState(false)
    const [loading, setLoading] = useState(true)
    const [ingesting, setIngesting] = useState(false)
    const [message, setMessage] = useState(null)
    const [timeRange, setTimeRange] = useState('SINCE_LAST')
    const [history, setHistory] = useState([])
    const [showHistory, setShowHistory] = useState(false)
    const [hasExistingConfig, setHasExistingConfig] = useState(false)

    const onChange = useCallback((field, value) => {
        setForm(prev => ({ ...prev, [field]: value }))
    }, [])

    // Load existing config
    useEffect(() => {
        if (!connectionId) {
            setLoading(false)
            return
        }
        let cancelled = false
        const load = async () => {
            try {
                setLoading(true)
                const config = await slowLogSourceAPI.getConfig(connectionId)
                if (!cancelled && config) {
                    setForm(prev => ({
                        ...prev,
                        ...config,
                        // Don't overwrite secret fields with redacted values
                        accessKeyId: '',
                        secretAccessKey: '',
                        azureConnectionString: '',
                        gcpServiceAccountJson: '',
                        datadogApiKey: '',
                        datadogAppKey: '',
                        elasticsearchApiKey: '',
                    }))
                    setHasExistingConfig(true)
                }
            } catch {
                // No config yet — that's fine
            } finally {
                if (!cancelled) setLoading(false)
            }
        }
        load()
        return () => { cancelled = true }
    }, [connectionId])

    const loadHistory = useCallback(async () => {
        if (!connectionId) return
        try {
            const data = await slowLogSourceAPI.getJobHistory(connectionId)
            setHistory(Array.isArray(data) ? data : [])
        } catch {
            setHistory([])
        }
    }, [connectionId])

    useEffect(() => {
        if (showHistory) loadHistory()
    }, [showHistory, loadHistory])

    const handleSave = async () => {
        if (!connectionId) return
        setSaving(true)
        setMessage(null)
        try {
            await slowLogSourceAPI.saveConfig({ ...form, connectionId })
            setHasExistingConfig(true)
            setMessage({ type: 'success', text: 'Configuration saved successfully.' })
        } catch (err) {
            setMessage({ type: 'error', text: err.message || 'Failed to save configuration.' })
        } finally {
            setSaving(false)
        }
    }

    const handleIngest = async () => {
        if (!connectionId) return
        setIngesting(true)
        setMessage(null)
        try {
            const provider = form.providerType
            const payload = {
                connectionId,
                timeRange,
                includeCloudWatch: provider === 'CLOUDWATCH',
                includeS3: provider === 'S3',
                includeAzureBlob: provider === 'AZURE_BLOB',
                includeGcpLogging: provider === 'GCP_LOGGING',
                includeDatadog: provider === 'DATADOG',
                includeElasticsearch: provider === 'ELASTICSEARCH',
            }
            const result = await slowLogSourceAPI.startAsyncIngestion(payload)
            setMessage({
                type: 'success',
                text: `Ingestion started (Job ${result.jobId ?? result.id ?? 'submitted'}). Check history for progress.`,
            })
        } catch (err) {
            setMessage({ type: 'error', text: err.message || 'Failed to start ingestion.' })
        } finally {
            setIngesting(false)
        }
    }

    if (!connectionId) {
        return (
            <div className={styles.emptyState}>
                <Cloud size={32} />
                <p>Select a database connection to configure slow query ingestion.</p>
            </div>
        )
    }

    if (loading) {
        return (
            <div className={styles.emptyState}>
                <Loader2 size={20} className={styles.spin} />
                <p>Loading configuration...</p>
            </div>
        )
    }

    return (
        <div className={styles.container}>
            <h3 className={styles.sectionTitle}>
                <Cloud size={16} />
                Slow Query Ingestion
            </h3>
            <p className={styles.sectionDesc}>
                Connect a cloud log source to automatically ingest and analyze slow queries.
            </p>

            {/* Provider selector */}
            <div className={styles.providerGrid}>
                {PROVIDERS.map(p => (
                    <button
                        key={p.key}
                        type="button"
                        className={`${styles.providerCard} ${form.providerType === p.key ? styles.providerCardActive : ''}`}
                        onClick={() => onChange('providerType', p.key)}
                    >
                        <span className={styles.providerIcon}>{p.icon}</span>
                        <span className={styles.providerLabel}>{p.label}</span>
                    </button>
                ))}
            </div>

            {/* Provider-specific fields */}
            <div className={styles.fieldsSection}>
                <ProviderFields
                    provider={form.providerType}
                    form={form}
                    onChange={onChange}
                />
            </div>

            {/* Schedule */}
            <div className={styles.scheduleSection}>
                <h4 className={styles.subTitle}>
                    <Clock size={14} />
                    Schedule
                </h4>
                <div className={styles.scheduleRow}>
                    <label className={styles.checkboxLabel}>
                        <input
                            type="checkbox"
                            checked={form.autoScheduleEnabled || false}
                            onChange={(e) => onChange('autoScheduleEnabled', e.target.checked)}
                        />
                        Auto-ingest on schedule
                    </label>
                    <div className={styles.freqWrap}>
                        <span className={styles.freqLabel}>Every</span>
                        <input
                            type="number"
                            className={styles.freqInput}
                            min={5}
                            max={1440}
                            value={form.refreshFrequencyMinutes || 60}
                            onChange={(e) => onChange('refreshFrequencyMinutes', parseInt(e.target.value, 10) || 60)}
                            disabled={!form.autoScheduleEnabled}
                        />
                        <span className={styles.freqLabel}>minutes</span>
                    </div>
                </div>
            </div>

            {/* Actions */}
            {message && (
                <div className={message.type === 'success' ? styles.msgSuccess : styles.msgError}>
                    {message.type === 'success'
                        ? <CheckCircle2 size={14} />
                        : <AlertCircle size={14} />}
                    {message.text}
                </div>
            )}

            <div className={styles.actions}>
                <button
                    type="button"
                    className={styles.saveBtn}
                    onClick={handleSave}
                    disabled={saving}
                >
                    {saving ? <Loader2 size={14} className={styles.spin} /> : <Upload size={14} />}
                    {hasExistingConfig ? 'Update Configuration' : 'Save Configuration'}
                </button>

                {hasExistingConfig && (
                    <div className={styles.ingestGroup}>
                        <select
                            className={styles.timeRangeSelect}
                            value={timeRange}
                            onChange={(e) => setTimeRange(e.target.value)}
                        >
                            {TIME_RANGES.map(tr => (
                                <option key={tr.key} value={tr.key}>{tr.label}</option>
                            ))}
                        </select>
                        <button
                            type="button"
                            className={styles.ingestBtn}
                            onClick={handleIngest}
                            disabled={ingesting}
                        >
                            {ingesting ? <Loader2 size={14} className={styles.spin} /> : <Play size={14} />}
                            Ingest Now
                        </button>
                    </div>
                )}
            </div>

            {/* Job history */}
            {hasExistingConfig && (
                <div className={styles.historySection}>
                    <button
                        type="button"
                        className={styles.historyToggle}
                        onClick={() => setShowHistory(v => !v)}
                    >
                        <History size={14} />
                        Ingestion History
                        {showHistory ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </button>
                    {showHistory && (
                        <div className={styles.historyList}>
                            <button
                                type="button"
                                className={styles.refreshHistoryBtn}
                                onClick={loadHistory}
                            >
                                <RefreshCw size={12} /> Refresh
                            </button>
                            {history.length === 0 ? (
                                <p className={styles.historyEmpty}>No ingestion jobs found.</p>
                            ) : (
                                history.map((job, i) => (
                                    <JobHistoryRow key={job.id || i} job={job} />
                                ))
                            )}
                        </div>
                    )}
                </div>
            )}
        </div>
    )
}
