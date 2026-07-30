'use client'

import { useState, useEffect } from 'react'
import {
    Settings,
    Zap,
    Check,
    X,
    Copy,
    RefreshCw,
    Loader2,
    AlertTriangle,
    TrendingUp,
    Database,
    AlertCircle,
    Info
} from 'lucide-react'
import { configAPI } from '@/lib/api/client'
import styles from './ConfigurationTunerTab.module.css'

export default function ConfigurationTunerTab({ connectionId }) {
    const [recommendations, setRecommendations] = useState([])
    const [loading, setLoading] = useState(false)
    const [analyzing, setAnalyzing] = useState(false)
    const [error, setError] = useState(null)
    const [copiedId, setCopiedId] = useState(null)
    const [filterCategory, setFilterCategory] = useState('ALL')

    useEffect(() => {
        if (connectionId) {
            fetchRecommendations()
        }
    }, [connectionId])

    const fetchRecommendations = async () => {
        setLoading(true)
        setError(null)

        try {
            const data = await configAPI.getConfiguration(connectionId)
            setRecommendations(data)
            console.log('Loaded configuration recommendations:', data.length)
        } catch (err) {
            console.error('Error loading recommendations:', err)
            setError('Error loading recommendations: ' + err.message)
        } finally {
            setLoading(false)
        }
    }

    const analyzeConfiguration = async () => {
        setAnalyzing(true)
        setError(null)

        try {
            const result = await configAPI.analyzeConfiguration(connectionId)
            console.log('Analysis result:', result)
            await fetchRecommendations()
        } catch (err) {
            console.error('Error analyzing configuration:', err)
            setError('Error analyzing configuration: ' + err.message)
        } finally {
            setAnalyzing(false)
        }
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

    const markAsApplied = async (id) => {
        try {
            await configAPI.applyRecommendation(id)
            await fetchRecommendations()
        } catch (err) {
            console.error('Error marking as applied:', err)
        }
    }

    const dismissRecommendation = async (id) => {
        try {
            await configAPI.dismissRecommendation(id)
            await fetchRecommendations()
        } catch (err) {
            console.error('Error dismissing recommendation:', err)
        }
    }

    const getFilteredRecommendations = () => {
        if (filterCategory === 'ALL') {
            return recommendations
        }
        return recommendations.filter(rec => rec.category === filterCategory)
    }

    const getCategories = () => {
        const categories = new Set(recommendations.map(r => r.category))
        return ['ALL', ...Array.from(categories)]
    }

    const getPriorityColor = (priority) => {
        switch (priority) {
            case 'CRITICAL': return 'var(--color-danger)'
            case 'HIGH': return 'var(--color-warning)'
            case 'MEDIUM': return 'var(--color-primary)'
            case 'LOW': return 'var(--color-primary)'
            default: return 'var(--color-light-6)'
        }
    }

    const getPriorityColors = (priority) => {
        switch (priority) {
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

    const getPriorityIcon = (priority) => {
        switch (priority) {
            case 'CRITICAL': return <AlertTriangle size={16} />
            case 'HIGH': return <AlertCircle size={16} />
            case 'MEDIUM': return <Info size={16} />
            case 'LOW': return <TrendingUp size={16} />
            default: return <Settings size={16} />
        }
    }

    const getStatistics = () => {
        const pending = recommendations.filter(r => r.status === 'PENDING').length
        const applied = recommendations.filter(r => r.status === 'APPLIED').length
        const critical = recommendations.filter(r => r.priority === 'CRITICAL' && r.status === 'PENDING').length
        const avgImprovement = recommendations
            .filter(r => r.estimatedImprovementPercent && r.status === 'PENDING')
            .reduce((sum, r) => sum + r.estimatedImprovementPercent, 0) /
            Math.max(1, recommendations.filter(r => r.estimatedImprovementPercent && r.status === 'PENDING').length)

        return { pending, applied, critical, avgImprovement: avgImprovement.toFixed(1) }
    }

    const filteredRecommendations = getFilteredRecommendations()
    const stats = getStatistics()

    if (!connectionId) {
        return (
            <div className={styles.emptyState}>
                <Database size={48} className={styles.emptyIcon} />
                <h3>No Connection Selected</h3>
                <p>Please select a database connection to analyze configuration.</p>
            </div>
        )
    }

    return (
        <div className={styles.container}>
            <div className={styles.header}>
                <div className={styles.titleSection}>
                    <h1>Database Configuration Tuner</h1>
                    <p className={styles.subtitle}>
                        AI-powered configuration analysis based on instance specifications and best practices
                    </p>
                </div>

                <div className={styles.actions}>
                    <button
                        className={styles.refreshButton}
                        onClick={fetchRecommendations}
                        disabled={loading}
                    >
                        <RefreshCw size={16} className={loading ? styles.spinning : ''} />
                        Refresh
                    </button>

                    <button
                        className={styles.analyzeButton}
                        onClick={analyzeConfiguration}
                        disabled={analyzing}
                    >
                        {analyzing ? (
                            <>
                                <Loader2 size={16} className={styles.spinning} />
                                Analyzing...
                            </>
                        ) : (
                            <>
                                <Zap size={16} />
                                Analyze Configuration
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

            {/* Statistics Cards */}
            {recommendations.length > 0 && (
                <div className={styles.statsCards}>
                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-warning-soft)' }}>
                            <Settings size={24} style={{ color: 'var(--color-warning)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Pending</div>
                            <div className={styles.statValue}>{stats.pending}</div>
                        </div>
                    </div>

                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-danger-soft)' }}>
                            <AlertTriangle size={24} style={{ color: 'var(--color-danger)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Critical</div>
                            <div className={styles.statValue}>{stats.critical}</div>
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
                        <div className={styles.statIcon} style={{ background: 'var(--color-light-2)' }}>
                            <TrendingUp size={24} style={{ color: 'var(--color-primary)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Avg Improvement</div>
                            <div className={styles.statValue}>{stats.avgImprovement}%</div>
                        </div>
                    </div>
                </div>
            )}

            {/* Category Filters */}
            {recommendations.length > 0 && (
                <div className={styles.filterTabs}>
                    {getCategories().map(category => (
                        <button
                            key={category}
                            className={`${styles.filterTab} ${filterCategory === category ? styles.activeFilter : ''}`}
                            onClick={() => setFilterCategory(category)}
                        >
                            {category}
                        </button>
                    ))}
                </div>
            )}

            {/* Recommendations List */}
            {loading ? (
                <div className={styles.loadingState}>
                    <Loader2 size={48} className={styles.spinner} />
                    <p>Loading configuration analysis...</p>
                </div>
            ) : filteredRecommendations.length === 0 ? (
                <div className={styles.emptyState}>
                    <Settings size={48} className={styles.emptyIcon} />
                    <h3>No Configuration Recommendations</h3>
                    <p>
                        Click "Analyze Configuration" to scan your database settings and get optimization recommendations.
                    </p>
                </div>
            ) : (
                <div className={styles.recommendationsList}>
                    {filteredRecommendations.map(rec => (
                        <div key={rec.id} className={styles.recommendationCard}>
                            <div className={styles.cardHeader}>
                                <div className={styles.parameterInfo}>
                                    <div
                                        className={styles.priorityBadge}
                                        style={{
                                            background: getPriorityColors(rec.priority).bg,
                                            color: getPriorityColors(rec.priority).text
                                        }}
                                    >
                                        {getPriorityIcon(rec.priority)}
                                        {rec.priority}
                                    </div>
                                    <h3>{rec.parameterName}</h3>
                                    <div className={styles.categoryBadge}>{rec.category}</div>
                                    {rec.requiresRestart && (
                                        <div className={styles.restartBadge}>
                                            <AlertTriangle size={12} />
                                            Requires Restart
                                        </div>
                                    )}
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
                                    </div>
                                )}

                                {rec.status === 'APPLIED' && (
                                    <div className={styles.appliedBadge}>
                                        <Check size={14} />
                                        Applied
                                    </div>
                                )}
                            </div>

                            <div className={styles.cardBody}>
                                {/* Comparison Table */}
                                <div className={styles.comparisonTable}>
                                    <div className={styles.comparisonRow}>
                                        <div className={styles.comparisonLabel}>Current Value</div>
                                        <div className={styles.currentValue}>{rec.currentValue}</div>
                                    </div>
                                    <div className={styles.comparisonRow}>
                                        <div className={styles.comparisonLabel}>Recommended</div>
                                        <div className={styles.recommendedValue}>{rec.recommendedValue}</div>
                                    </div>
                                    {rec.estimatedImprovementPercent && (
                                        <div className={styles.comparisonRow}>
                                            <div className={styles.comparisonLabel}>Est. Improvement</div>
                                            <div className={styles.improvementValue}>
                                                <TrendingUp size={14} />
                                                {rec.estimatedImprovementPercent}%
                                            </div>
                                        </div>
                                    )}
                                </div>

                                {/* Reasoning */}
                                <div className={styles.reasoning}>
                                    <strong>Why:</strong> {rec.reasoning}
                                </div>

                                {/* Impact */}
                                <div className={styles.impact}>
                                    <strong>Impact:</strong> {rec.impactDescription}
                                </div>

                                {/* SQL Command */}
                                <div className={styles.sqlSection}>
                                    <div className={styles.sqlHeader}>
                                        <span className={styles.sqlLabel}>SQL Command</span>
                                        <button
                                            className={styles.copyButton}
                                            onClick={() => copyToClipboard(rec.applySql, rec.id)}
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
                                    <pre className={styles.sqlCode}>{rec.applySql}</pre>
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    )
}
