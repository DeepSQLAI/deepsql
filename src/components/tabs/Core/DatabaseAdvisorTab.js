'use client'

import { useState, useMemo } from 'react'
import { AlertCircle, CheckCircle, AlertTriangle, Info, Copy, Check, Loader2, RefreshCw } from 'lucide-react'
import { useAdvisorAnalysis } from '@/lib/hooks/queries'
import { ConfigTuningPanel } from '../Brain'
import styles from './DatabaseAdvisorTab.module.css'

export default function DatabaseAdvisorTab({ connectionId }) {
    // UI state only
    const [copiedSql, setCopiedSql] = useState(null)

    // Server state with TanStack Query
    const {
        data: analysisData,
        isLoading: loading,
        error: queryError,
        refetch: refetchAnalysis,
    } = useAdvisorAnalysis(connectionId)

    // Derived state
    const analysis = useMemo(() => analysisData || null, [analysisData])
    const error = queryError?.message || null

    const fetchAnalysis = () => {
        refetchAnalysis()
    }

    const copyToClipboard = async (sql, id) => {
        try {
            await navigator.clipboard.writeText(sql)
            setCopiedSql(id)
            setTimeout(() => setCopiedSql(null), 2000)
        } catch (err) {
            console.error('Failed to copy:', err)
        }
    }

    const getHealthColor = (health) => {
        switch (health) {
            case 'EXCELLENT': return 'var(--color-success)'
            case 'GOOD': return 'var(--color-primary)'
            case 'FAIR': return 'var(--color-warning)'
            case 'POOR': return 'var(--color-danger)'
            case 'CRITICAL': return 'var(--color-danger)'
            default: return 'var(--color-light-6)'
        }
    }

    const getHealthIcon = (health) => {
        switch (health) {
            case 'EXCELLENT':
            case 'GOOD':
                return <CheckCircle size={24} />
            case 'FAIR':
                return <Info size={24} />
            case 'POOR':
            case 'CRITICAL':
                return <AlertCircle size={24} />
            default:
                return <AlertTriangle size={24} />
        }
    }

    const getPriorityColor = (priority) => {
        switch (priority) {
            case 'CRITICAL': return 'var(--color-danger)'
            case 'HIGH': return 'var(--color-danger)'
            case 'MEDIUM': return 'var(--color-warning)'
            case 'LOW': return 'var(--color-primary)'
            default: return 'var(--color-light-6)'
        }
    }

    const getPriorityIcon = (priority) => {
        switch (priority) {
            case 'CRITICAL':
            case 'HIGH':
                return <AlertCircle size={16} />
            case 'MEDIUM':
                return <AlertTriangle size={16} />
            case 'LOW':
                return <Info size={16} />
            default:
                return null
        }
    }

    if (!connectionId) {
        return (
            <div className={styles.container}>
                <div className={styles.emptyState}>
                    <AlertCircle size={48} />
                    <h3>No Connection Selected</h3>
                    <p>Please select a database connection to analyze performance</p>
                </div>
            </div>
        )
    }

    if (loading) {
        return (
            <div className={styles.container}>
                <div className={styles.loadingState}>
                    <Loader2 size={48} className={styles.spinner} />
                    <h3>Analyzing Database Performance</h3>
                    <p>Scanning for missing indexes, schema issues, and optimization opportunities...</p>
                </div>
            </div>
        )
    }

    if (error) {
        return (
            <div className={styles.container}>
                <div className={styles.errorState}>
                    <AlertCircle size={48} />
                    <h3>Analysis Failed</h3>
                    <p>{error}</p>
                    <button onClick={fetchAnalysis} className={styles.retryButton}>
                        Retry Analysis
                    </button>
                </div>
            </div>
        )
    }

    if (!analysis) {
        return null
    }

    const totalRecommendations = analysis.indexRecommendations.length + analysis.generalRecommendations.length
    const criticalCount = [...analysis.indexRecommendations, ...analysis.generalRecommendations]
        .filter(r => r.priority === 'CRITICAL').length
    const highCount = [...analysis.indexRecommendations, ...analysis.generalRecommendations]
        .filter(r => r.priority === 'HIGH').length

    return (
        <div className={styles.container}>
            {/* Header with Health Status */}
            <div className={styles.header}>
                <div className={styles.healthCard} style={{ borderColor: getHealthColor(analysis.overallHealth) }}>
                    <div className={styles.healthIcon} style={{ color: getHealthColor(analysis.overallHealth) }}>
                        {getHealthIcon(analysis.overallHealth)}
                    </div>
                    <div className={styles.healthInfo}>
                        <div className={styles.healthLabel}>Overall Health</div>
                        <div className={styles.healthStatus} style={{ color: getHealthColor(analysis.overallHealth) }}>
                            {analysis.overallHealth}
                        </div>
                        <div className={styles.healthStats}>
                            <span>{totalRecommendations} recommendations</span>
                            {criticalCount > 0 && <span className={styles.critical}>{criticalCount} critical</span>}
                            {highCount > 0 && <span className={styles.high}>{highCount} high priority</span>}
                        </div>
                    </div>
                </div>

                <div className={styles.headerActions}>
                    <button onClick={fetchAnalysis} className={styles.refreshButton} title="Refresh Analysis">
                        <RefreshCw size={14} />
                    </button>
                </div>
            </div>

            {/* Content Sections */}
            <div className={styles.content}>
                {/* Overview Section */}
                <div className={styles.overviewSection}>
                        {/* AI Summary */}
                        {analysis.aiSummary && (
                            <div className={styles.aiSummary}>
                                <h3>AI-Powered Analysis</h3>
                                <div className={styles.summaryContent}>
                                    {analysis.aiSummary.split('\n').map((line, idx) => (
                                        <p key={idx}>{line}</p>
                                    ))}
                                </div>
                            </div>
                        )}

                        {/* Top Issues */}
                        <div className={styles.topIssues}>
                            <h3>Top Priority Issues</h3>
                            {[...analysis.indexRecommendations, ...analysis.generalRecommendations]
                                .sort((a, b) => {
                                    const priorityOrder = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 }
                                    return priorityOrder[a.priority] - priorityOrder[b.priority]
                                })
                                .slice(0, 5)
                                .map((rec, idx) => (
                                    <div key={idx} className={styles.issueCard}>
                                        <div className={styles.issueHeader}>
                                            <div className={styles.priorityBadge} style={{ backgroundColor: getPriorityColor(rec.priority) }}>
                                                {getPriorityIcon(rec.priority)}
                                                <span>{rec.priority}</span>
                                            </div>
                                            <div className={styles.issueTitle}>
                                                {rec.tableName ? `Table: ${rec.schemaName ? `${rec.schemaName}.${rec.tableName}` : rec.tableName}` : rec.title}
                                            </div>
                                        </div>
                                        <div className={styles.issueReasoning}>
                                            {rec.reasoning || rec.description}
                                        </div>
                                        {rec.suggestedSQL && (
                                            <div className={styles.sqlBlock}>
                                                <code>{rec.suggestedSQL}</code>
                                                <button
                                                    className={styles.copyButton}
                                                    onClick={() => copyToClipboard(rec.suggestedSQL, `overview-${idx}`)}
                                                    title="Copy SQL"
                                                >
                                                    {copiedSql === `overview-${idx}` ? <Check size={16} /> : <Copy size={16} />}
                                                </button>
                                            </div>
                                        )}
                                    </div>
                                ))}
                        </div>
                </div>

                {/* Config Tuning Section - Brain 2.0 ML Feature */}
                <ConfigTuningPanel connectionId={connectionId} />
            </div>
        </div>
    )
}
