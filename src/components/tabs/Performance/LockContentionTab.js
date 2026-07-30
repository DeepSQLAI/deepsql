'use client'

import { useState, useMemo } from 'react'
import {
    Shield,
    AlertTriangle,
    RefreshCw,
    Loader2,
    Zap,
    Database,
    Clock,
    User,
    Terminal,
    AlertCircle,
    XCircle,
    Lock,
    Unlock,
    TrendingUp,
    Activity
} from 'lucide-react'
import {
    useActiveLocks,
    useLockStatistics,
    useDetectContentions,
    useKillSession,
} from '@/lib/hooks/queries'
import styles from './LockContentionTab.module.css'

export default function LockContentionTab({ connectionId }) {
    // UI state only
    const [autoRefresh, setAutoRefresh] = useState(false)
    const [filterSeverity, setFilterSeverity] = useState('ALL')
    const [expandedCards, setExpandedCards] = useState(new Set())

    // Server state with TanStack Query
    const {
        data: contentionsData = [],
        isLoading: loading,
        error: contentionsError,
        refetch: refetchContentions,
    } = useActiveLocks(connectionId, { autoRefresh, interval: 5000 })

    const { data: statisticsData } = useLockStatistics(connectionId)

    // Mutations
    const detectContentionsMutation = useDetectContentions()
    const killSessionMutation = useKillSession()

    // Derived state
    const contentions = useMemo(() => contentionsData || [], [contentionsData])
    const statistics = useMemo(() => statisticsData || null, [statisticsData])
    const detecting = detectContentionsMutation.isPending
    const error = contentionsError?.message ||
        detectContentionsMutation.error?.message ||
        killSessionMutation.error?.message ||
        null

    const detectContentions = async () => {
        detectContentionsMutation.mutate(connectionId)
    }

    const killSession = async (pid) => {
        if (!confirm(`Are you sure you want to kill session ${pid}? This will terminate the query and rollback any uncommitted changes.`)) {
            return
        }

        killSessionMutation.mutate(
            { connectionId, pid },
            {
                onSuccess: () => {
                    refetchContentions()
                },
            }
        )
    }


    const toggleCardExpansion = (id) => {
        const newExpanded = new Set(expandedCards)
        if (newExpanded.has(id)) {
            newExpanded.delete(id)
        } else {
            newExpanded.add(id)
        }
        setExpandedCards(newExpanded)
    }

    const getSeverityColor = (severity) => {
        switch (severity) {
            case 'CRITICAL': return 'var(--color-danger)'
            case 'HIGH': return 'var(--color-warning)'
            case 'MEDIUM': return 'var(--color-primary)'
            case 'LOW': return 'var(--color-primary)'
            default: return 'var(--color-light-6)'
        }
    }

    const getSeverityColors = (severity) => {
        switch (severity) {
            case 'CRITICAL':
                return { text: 'var(--color-danger)', bg: 'var(--color-danger-soft)' }
            case 'HIGH':
                return { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' }
            case 'MEDIUM':
                return { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' }
            case 'LOW':
                return { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' }
            default:
                return { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
        }
    }

    const getSeverityIcon = (severity) => {
        switch (severity) {
            case 'CRITICAL': return <AlertTriangle size={16} />
            case 'HIGH': return <AlertCircle size={16} />
            case 'MEDIUM': return <Lock size={16} />
            case 'LOW': return <Activity size={16} />
            default: return <Shield size={16} />
        }
    }

    const formatDuration = (seconds) => {
        if (!seconds) return '0s'
        if (seconds < 60) return `${seconds}s`
        const minutes = Math.floor(seconds / 60)
        const remainingSeconds = seconds % 60
        return `${minutes}m ${remainingSeconds}s`
    }

    const getFilteredContentions = () => {
        if (filterSeverity === 'ALL') {
            return contentions
        }
        return contentions.filter(c => c.severity === filterSeverity)
    }

    const getSeverities = () => {
        const severities = new Set(contentions.map(c => c.severity))
        return ['ALL', ...Array.from(severities)]
    }

    const filteredContentions = getFilteredContentions()

    if (!connectionId) {
        return (
            <div className={styles.emptyState}>
                <Database size={48} className={styles.emptyIcon} />
                <h3>No Connection Selected</h3>
                <p>Please select a database connection to monitor lock contention.</p>
            </div>
        )
    }

    return (
        <div className={styles.container}>
            <div className={styles.header}>
                <div className={styles.titleSection}>
                    <h1>Lock Contention Analyzer</h1>
                    <p className={styles.subtitle}>
                        Real-time monitoring of blocking and blocked queries
                    </p>
                </div>

                <div className={styles.actions}>
                    <label className={styles.autoRefreshToggle}>
                        <input
                            type="checkbox"
                            checked={autoRefresh}
                            onChange={(e) => setAutoRefresh(e.target.checked)}
                        />
                        <span>Auto-refresh (5s)</span>
                    </label>

                    <button
                        className={styles.refreshButton}
                        onClick={() => refetchContentions()}
                        disabled={loading}
                    >
                        <RefreshCw size={16} className={loading ? styles.spinning : ''} />
                        Refresh
                    </button>

                    <button
                        className={styles.detectButton}
                        onClick={detectContentions}
                        disabled={detecting}
                    >
                        {detecting ? (
                            <>
                                <Loader2 size={16} className={styles.spinning} />
                                Detecting...
                            </>
                        ) : (
                            <>
                                <Zap size={16} />
                                Detect Now
                            </>
                        )}
                    </button>
                </div>
            </div>

            {error && (
                <div className={styles.errorBanner}>
                    <AlertCircle size={16} />
                    <span>{error}</span>
                </div>
            )}

            {/* Statistics Dashboard */}
            {statistics && (
                <div className={styles.statsCards}>
                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-light-2)' }}>
                            <Lock size={24} style={{ color: 'var(--color-primary)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Total Active</div>
                            <div className={styles.statValue}>{statistics.total || 0}</div>
                        </div>
                    </div>

                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-danger-soft)' }}>
                            <AlertTriangle size={24} style={{ color: 'var(--color-danger)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Critical</div>
                            <div className={styles.statValue}>{statistics.critical || 0}</div>
                        </div>
                    </div>

                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-warning-soft)' }}>
                            <AlertCircle size={24} style={{ color: 'var(--color-warning)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>High</div>
                            <div className={styles.statValue}>{statistics.high || 0}</div>
                        </div>
                    </div>

                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-light-2)' }}>
                            <Clock size={24} style={{ color: 'var(--color-dark-grey)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Avg Wait Time</div>
                            <div className={styles.statValue}>
                                {formatDuration(Math.round(statistics.avgWaitSeconds || 0))}
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Severity Filters */}
            {contentions.length > 0 && (
                <div className={styles.filterTabs}>
                    {getSeverities().map(severity => (
                        <button
                            key={severity}
                            className={`${styles.filterTab} ${filterSeverity === severity ? styles.activeFilter : ''}`}
                            onClick={() => setFilterSeverity(severity)}
                        >
                            {severity}
                        </button>
                    ))}
                </div>
            )}

            {/* Contentions List */}
            {loading ? (
                <div className={styles.loadingState}>
                    <Loader2 size={48} className={styles.spinner} />
                    <p>Loading lock contentions...</p>
                </div>
            ) : filteredContentions.length === 0 ? (
                <div className={styles.emptyState}>
                    <Unlock size={48} className={styles.emptyIcon} />
                    <h3>No Lock Contentions Detected</h3>
                    <p>
                        {contentions.length === 0
                            ? 'Click "Detect Now" to scan for blocking queries.'
                            : 'No contentions match the selected severity filter.'}
                    </p>
                </div>
            ) : (
                <div className={styles.contentionsList}>
                    {filteredContentions.map(contention => (
                        <div key={contention.id} className={styles.contentionCard}>
                            <div className={styles.cardHeader}>
                                <div className={styles.contentionInfo}>
                                    <div
                                        className={styles.severityBadge}
                                        style={{
                                            background: getSeverityColors(contention.severity).bg,
                                            color: getSeverityColors(contention.severity).text
                                        }}
                                    >
                                        {getSeverityIcon(contention.severity)}
                                        {contention.severity}
                                    </div>

                                    <div className={styles.lockTypeBadge}>
                                        <Lock size={12} />
                                        {contention.lockType}
                                    </div>

                                    <div className={styles.waitTime}>
                                        <Clock size={14} />
                                        Waiting: {formatDuration(contention.waitDurationSeconds)}
                                    </div>

                                    {contention.tableName && (
                                        <div className={styles.tableBadge}>
                                            <Database size={12} />
                                            {contention.tableName}
                                        </div>
                                    )}
                                </div>

                                <button
                                    className={styles.killButton}
                                    onClick={() => killSession(contention.blockingPid)}
                                    title="Kill blocking session"
                                >
                                    <XCircle size={16} />
                                    Kill PID {contention.blockingPid}
                                </button>
                            </div>

                            <div className={styles.cardBody}>
                                {/* Blocking Query Section */}
                                <div className={styles.querySection}>
                                    <div className={styles.querySectionHeader}>
                                        <div className={styles.queryLabel}>
                                            <AlertTriangle size={16} style={{ color: 'var(--color-danger)' }} />
                                            <strong>Blocking Query (PID: {contention.blockingPid})</strong>
                                        </div>
                                        <div className={styles.queryMeta}>
                                            {contention.blockingUser && (
                                                <span className={styles.metaItem}>
                                                    <User size={12} />
                                                    {contention.blockingUser}
                                                </span>
                                            )}
                                            {contention.blockingApplication && (
                                                <span className={styles.metaItem}>
                                                    <Terminal size={12} />
                                                    {contention.blockingApplication}
                                                </span>
                                            )}
                                            {contention.blockingState && (
                                                <span className={styles.stateBadge}>
                                                    {contention.blockingState}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                    <pre className={styles.queryCode}>
                                        {contention.blockingQuery || 'No query information available'}
                                    </pre>
                                </div>

                                {/* Blocked Query Section */}
                                <div className={styles.querySection}>
                                    <div className={styles.querySectionHeader}>
                                        <div className={styles.queryLabel}>
                                            <Lock size={16} style={{ color: 'var(--color-warning)' }} />
                                            <strong>Blocked Query (PID: {contention.blockedPid})</strong>
                                        </div>
                                        <div className={styles.queryMeta}>
                                            {contention.blockedUser && (
                                                <span className={styles.metaItem}>
                                                    <User size={12} />
                                                    {contention.blockedUser}
                                                </span>
                                            )}
                                            {contention.blockedApplication && (
                                                <span className={styles.metaItem}>
                                                    <Terminal size={12} />
                                                    {contention.blockedApplication}
                                                </span>
                                            )}
                                            {contention.blockedState && (
                                                <span className={styles.stateBadge}>
                                                    {contention.blockedState}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                    <pre className={styles.queryCode}>
                                        {contention.blockedQuery || 'No query information available'}
                                    </pre>
                                </div>

                                {/* Lock Details */}
                                {(contention.lockMode || contention.waitEvent) && (
                                    <div className={styles.lockDetails}>
                                        {contention.lockMode && (
                                            <div className={styles.detailItem}>
                                                <strong>Lock Mode:</strong> {contention.lockMode}
                                            </div>
                                        )}
                                        {contention.lockTarget && (
                                            <div className={styles.detailItem}>
                                                <strong>Lock Target:</strong> {contention.lockTarget}
                                            </div>
                                        )}
                                        {contention.waitEvent && (
                                            <div className={styles.detailItem}>
                                                <strong>Wait Event:</strong> {contention.waitEvent}
                                            </div>
                                        )}
                                        {contention.databaseName && (
                                            <div className={styles.detailItem}>
                                                <strong>Database:</strong> {contention.databaseName}
                                            </div>
                                        )}
                                    </div>
                                )}
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    )
}
