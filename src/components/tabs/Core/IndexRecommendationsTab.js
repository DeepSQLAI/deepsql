'use client'

import { useState, useMemo } from 'react'
import {
    Zap,
    Copy,
    Check,
    X,
    Trash2,
    AlertCircle,
    TrendingUp,
    Loader2,
    RefreshCw,
    Database,
    Activity
} from 'lucide-react'
import {
    useIndexRecommendations,
    useGenerateIndexRecommendations,
    useApplyIndexRecommendation,
    useDismissIndexRecommendation,
    useDeleteIndexRecommendation,
} from '@/lib/hooks/queries'
import styles from './IndexRecommendationsTab.module.css'

export default function IndexRecommendationsTab({ connectionId }) {
    // UI state only
    const [copiedId, setCopiedId] = useState(null)
    const [filterStatus, setFilterStatus] = useState('ALL') // ALL, PENDING, APPLIED, DISMISSED

    // Server state with TanStack Query
    const {
        data: recommendationsData = [],
        isLoading: loading,
        error: queryError,
        refetch: refetchRecommendations,
    } = useIndexRecommendations(connectionId)

    // Mutations
    const generateMutation = useGenerateIndexRecommendations()
    const applyMutation = useApplyIndexRecommendation()
    const dismissMutation = useDismissIndexRecommendation()
    const deleteMutation = useDeleteIndexRecommendation()

    // Derived state
    const recommendations = useMemo(() => recommendationsData || [], [recommendationsData])
    const generating = generateMutation.isPending
    const error = queryError?.message ||
        generateMutation.error?.message ||
        applyMutation.error?.message ||
        dismissMutation.error?.message ||
        deleteMutation.error?.message ||
        null

    const generateRecommendations = () => {
        generateMutation.mutate(connectionId)
    }

    const copyToClipboard = async (sql, id) => {
        try {
            await navigator.clipboard.writeText(sql)
            setCopiedId(id)
            setTimeout(() => setCopiedId(null), 2000)
        } catch (err) {
            console.error('Failed to copy:', err)
        }
    }

    const markAsApplied = (id) => {
        applyMutation.mutate(id)
    }

    const dismissRecommendation = (id) => {
        dismissMutation.mutate(id)
    }

    const deleteRecommendation = (id) => {
        if (!confirm('Are you sure you want to delete this recommendation?')) {
            return
        }
        deleteMutation.mutate(id)
    }

    const getFilteredRecommendations = () => {
        if (filterStatus === 'ALL') {
            return recommendations
        }
        return recommendations.filter(rec => rec.status === filterStatus)
    }

    const getStatistics = () => {
        const pending = recommendations.filter(r => r.status === 'PENDING').length
        const applied = recommendations.filter(r => r.status === 'APPLIED').length
        const dismissed = recommendations.filter(r => r.status === 'DISMISSED').length

        return { pending, applied, dismissed, total: recommendations.length }
    }

    const getPriorityColor = (priority) => {
        switch (priority) {
            case 'HIGH': return 'var(--color-danger)'
            case 'MEDIUM': return 'var(--color-warning)'
            case 'LOW': return 'var(--color-primary)'
            default: return 'var(--color-light-6)'
        }
    }

    const getPriorityColors = (priority) => {
        switch (priority) {
            case 'HIGH':
                return { text: 'var(--color-danger)', bg: 'var(--color-danger-soft)' }
            case 'MEDIUM':
                return { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' }
            case 'LOW':
                return { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' }
            default:
                return { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
        }
    }

    const getPriorityIcon = (priority) => {
        switch (priority) {
            case 'HIGH': return <AlertCircle size={16} />
            case 'MEDIUM': return <TrendingUp size={16} />
            case 'LOW': return <Activity size={16} />
            default: return <Zap size={16} />
        }
    }

    const stats = getStatistics()
    const filteredRecommendations = getFilteredRecommendations()

    if (!connectionId) {
        return (
            <div className={styles.emptyState}>
                <Database size={48} className={styles.emptyIcon} />
                <h3>No Connection Selected</h3>
                <p>Please select a database connection to view index recommendations.</p>
            </div>
        )
    }

    return (
        <div className={styles.container}>
            <div className={styles.header}>
                <div className={styles.titleSection}>
                    <h1>Index Recommendations</h1>
                    <p className={styles.subtitle}>
                        AI-powered index suggestions from schema analysis, query patterns, and performance data
                    </p>
                </div>

                <div className={styles.actions}>
                    <button
                        className={styles.refreshButton}
                        onClick={() => refetchRecommendations()}
                        disabled={loading}
                        title="Refresh"
                    >
                        <RefreshCw size={14} className={loading ? styles.spinning : ''} />
                    </button>

                    <button
                        className={styles.generateButton}
                        onClick={generateRecommendations}
                        disabled={generating}
                        title="Generate Recommendations"
                    >
                        {generating ? (
                            <Loader2 size={14} className={styles.spinning} />
                        ) : (
                            <Zap size={14} />
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

            {/* Statistics Cards */}
            <div className={styles.statsCards}>
                <div className={styles.statCard}>
                    <div className={styles.statIcon} style={{ background: 'var(--color-primary-light)' }}>
                        <Database size={24} style={{ color: 'var(--color-primary)' }} />
                    </div>
                    <div className={styles.statContent}>
                        <div className={styles.statLabel}>Total</div>
                        <div className={styles.statValue}>{stats.total}</div>
                    </div>
                </div>

                <div className={styles.statCard}>
                    <div className={styles.statIcon} style={{ background: 'var(--color-warning-soft)' }}>
                        <AlertCircle size={24} style={{ color: 'var(--color-warning)' }} />
                    </div>
                    <div className={styles.statContent}>
                        <div className={styles.statLabel}>Pending</div>
                        <div className={styles.statValue}>{stats.pending}</div>
                    </div>
                </div>

                <div className={styles.statCard}>
                    <div className={styles.statIcon} style={{ background: 'var(--color-success-soft)' }}>
                        <Check size={24} style={{ color: 'var(--color-success)' }} />
                    </div>
                    <div className={styles.statContent}>
                        <div className={styles.statLabel}>Applied</div>
                        <div className={styles.statValue}>{stats.applied}</div>
                    </div>
                </div>

                <div className={styles.statCard}>
                    <div className={styles.statIcon} style={{ background: 'var(--color-danger-soft)' }}>
                        <X size={24} style={{ color: 'var(--color-danger)' }} />
                    </div>
                    <div className={styles.statContent}>
                        <div className={styles.statLabel}>Dismissed</div>
                        <div className={styles.statValue}>{stats.dismissed}</div>
                    </div>
                </div>
            </div>

            {/* Filter Tabs */}
            <div className={styles.filterTabs}>
                {['ALL', 'PENDING', 'APPLIED', 'DISMISSED'].map(status => (
                    <button
                        key={status}
                        className={`${styles.filterTab} ${filterStatus === status ? styles.activeFilter : ''}`}
                        onClick={() => setFilterStatus(status)}
                    >
                        {status}
                        {status !== 'ALL' && (
                            <span className={styles.filterCount}>
                                {recommendations.filter(r => r.status === status).length}
                            </span>
                        )}
                    </button>
                ))}
            </div>

            {/* Recommendations List */}
            {loading ? (
                <div className={styles.loadingState}>
                    <Loader2 size={48} className={styles.spinner} />
                    <p>Loading recommendations...</p>
                </div>
            ) : filteredRecommendations.length === 0 ? (
                <div className={styles.emptyState}>
                    <Zap size={48} className={styles.emptyIcon} />
                    <h3>No Recommendations</h3>
                    <p>
                        {filterStatus === 'ALL'
                            ? 'Click "Generate Recommendations" to analyze your database schema, query patterns, and get intelligent index suggestions.'
                            : `No ${filterStatus.toLowerCase()} recommendations found.`}
                    </p>
                </div>
            ) : (
                <div className={styles.recommendationsList}>
                    {filteredRecommendations.map(rec => (
                        <div key={rec.id} className={styles.recommendationCard}>
                            <div className={styles.cardHeader}>
                                <div className={styles.cardTitle}>
                                    <div
                                        className={styles.priorityBadge}
                                        style={{ background: getPriorityColors(rec.priority).bg, color: getPriorityColors(rec.priority).text }}
                                    >
                                        {getPriorityIcon(rec.priority)}
                                        {rec.priority}
                                    </div>
                                    <h3>{rec.indexName}</h3>
                                    <div className={styles.statusBadge} data-status={rec.status}>
                                        {rec.status}
                                    </div>
                                </div>

                                {rec.status === 'PENDING' && (
                                    <div className={styles.cardActions}>
                                        <button
                                            className={styles.applyButton}
                                            onClick={() => markAsApplied(rec.id)}
                                            title="Mark as applied"
                                        >
                                            <Check size={16} />
                                        </button>
                                        <button
                                            className={styles.dismissButton}
                                            onClick={() => dismissRecommendation(rec.id)}
                                            title="Dismiss"
                                        >
                                            <X size={16} />
                                        </button>
                                        <button
                                            className={styles.deleteButton}
                                            onClick={() => deleteRecommendation(rec.id)}
                                            title="Delete"
                                        >
                                            <Trash2 size={16} />
                                        </button>
                                    </div>
                                )}

                                {rec.status !== 'PENDING' && (
                                    <button
                                        className={styles.deleteButton}
                                        onClick={() => deleteRecommendation(rec.id)}
                                        title="Delete"
                                    >
                                        <Trash2 size={16} />
                                    </button>
                                )}
                            </div>

                            <div className={styles.cardBody}>
                                <div className={styles.infoRow}>
                                    <div className={styles.infoItem}>
                                        <span className={styles.infoLabel}>Table:</span>
                                        <span className={styles.infoValue}>{rec.tableName}</span>
                                    </div>
                                    <div className={styles.infoItem}>
                                        <span className={styles.infoLabel}>Columns:</span>
                                        <span className={styles.infoValue}>{rec.columnNames}</span>
                                    </div>
                                    {rec.estimatedImpact && (
                                        <div className={styles.infoItem}>
                                            <span className={styles.infoLabel}>Est. Impact:</span>
                                            <span className={styles.impactValue}>{rec.estimatedImpact}%</span>
                                        </div>
                                    )}
                                    {rec.affectedQueries && (
                                        <div className={styles.infoItem}>
                                            <span className={styles.infoLabel}>Affected Queries:</span>
                                            <span className={styles.infoValue}>{rec.affectedQueries}</span>
                                        </div>
                                    )}
                                </div>

                                {rec.reason && (
                                    <div className={styles.reason}>
                                        <AlertCircle size={14} />
                                        <span>{rec.reason}</span>
                                    </div>
                                )}

                                <div className={styles.sqlSection}>
                                    <div className={styles.sqlHeader}>
                                        <span className={styles.sqlLabel}>SQL Statement</span>
                                        <button
                                            className={styles.copyButton}
                                            onClick={() => copyToClipboard(rec.createStatement, rec.id)}
                                        >
                                            {copiedId === rec.id ? (
                                                <>
                                                    <Check size={14} />
                                                    Copied!
                                                </>
                                            ) : (
                                                <>
                                                    <Copy size={14} />
                                                    Copy
                                                </>
                                            )}
                                        </button>
                                    </div>
                                    <pre className={styles.sqlCode}>{rec.createStatement}</pre>
                                </div>

                                {rec.appliedAt && (
                                    <div className={styles.timestamp}>
                                        Applied: {new Date(rec.appliedAt).toLocaleString()}
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
