'use client'

import { useState, useEffect, useMemo, useCallback } from 'react'
import {
    AlertTriangle, TrendingUp, TrendingDown, Activity, Zap,
    Clock, Target, CheckCircle, XCircle, RefreshCw, Calendar,
    Database, Cpu, HardDrive, Network, Settings, Play
} from 'lucide-react'
import { resourceLimitsAPI, monitoringAPI } from '@/lib/api/client'
import {
    useDeathClock,
    useForecasts,
    useDatabaseEvents,
    useSentinelRecommendations,
    useSentinelSummary,
    useUpdateRecommendationStatus,
} from '@/lib/hooks/queries'
import ResourceLimitsSetupWizard from '../ResourceLimitsSetupWizard'
import styles from './SentinelAnalyticsTab.module.css'

export default function SentinelAnalyticsTab({ connectionId }) {
    // UI state only
    const [activeView, setActiveView] = useState('deathClock')
    const [showSetupWizard, setShowSetupWizard] = useState(false)
    const [snapshotLoading, setSnapshotLoading] = useState(false)
    const [hasResourceLimits, setHasResourceLimits] = useState(false)

    // Server state with TanStack Query
    const {
        data: deathClockData,
        isLoading: loadingDeathClock,
        error: deathClockError,
        refetch: refetchDeathClock,
    } = useDeathClock(connectionId)

    const {
        data: forecastsData,
        refetch: refetchForecasts,
    } = useForecasts(connectionId)

    const {
        data: eventsData,
        refetch: refetchEvents,
    } = useDatabaseEvents(connectionId, 30)

    const {
        data: recommendationsData,
        refetch: refetchRecommendations,
    } = useSentinelRecommendations(connectionId)

    const {
        data: summaryData,
        refetch: refetchSummary,
    } = useSentinelSummary(connectionId)

    // Mutations
    const updateRecommendationMutation = useUpdateRecommendationStatus()

    // Derived state
    const deathClock = useMemo(() => deathClockData || null, [deathClockData])
    const forecasts = useMemo(() => forecastsData?.forecasts || [], [forecastsData])
    const events = useMemo(() => eventsData?.events || [], [eventsData])
    const recommendations = useMemo(() => recommendationsData?.recommendations || [], [recommendationsData])
    const summary = useMemo(() => summaryData || null, [summaryData])
    const loading = loadingDeathClock
    const error = deathClockError?.message || null

    // Check resource limits on mount/connectionId change
    const checkResourceLimits = useCallback(async () => {
        if (!connectionId) return
        try {
            const response = await resourceLimitsAPI.getResourceLimits(connectionId)
            setHasResourceLimits(response.exists !== false && response.maxStorageBytes != null)
        } catch (err) {
            console.error('Failed to check resource limits:', err)
            setHasResourceLimits(false)
        }
    }, [connectionId])

    useEffect(() => {
        checkResourceLimits()
    }, [checkResourceLimits])

    const handleTriggerSnapshot = async () => {
        setSnapshotLoading(true)
        try {
            await monitoringAPI.triggerSnapshot(connectionId)
            // Reload data after snapshot
            loadData()
            alert('Snapshot captured successfully! Data will be available for analysis.')
        } catch (err) {
            console.error('Failed to trigger snapshot:', err)
            alert('Failed to trigger snapshot: ' + err.message)
        } finally {
            setSnapshotLoading(false)
        }
    }

    const loadData = () => {
        refetchDeathClock()
        refetchForecasts()
        refetchEvents()
        refetchRecommendations()
        refetchSummary()
    }

    const handleUpdateRecommendation = (recommendationId, status) => {
        updateRecommendationMutation.mutate({ recommendationId, status })
    }

    const formatDate = (dateString) => {
        if (!dateString) return 'N/A'
        const date = new Date(dateString)
        return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
    }

    const formatDateTime = (dateString) => {
        if (!dateString) return 'N/A'
        const date = new Date(dateString)
        return date.toLocaleString('en-US', {
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        })
    }

    const getDaysRemainingColor = (days) => {
        if (days === null || days === undefined) return 'gray'
        if (days <= 7) return 'var(--color-danger)'  // Red - Critical
        if (days <= 30) return 'var(--color-warning)'  // Orange - Warning
        if (days <= 90) return 'var(--color-primary)'  // Blue - Info
        return 'var(--color-success)'  // Green - OK
    }

    const getSeverityBadge = (riskScore) => {
        if (riskScore >= 8) return { label: 'CRITICAL', color: 'var(--color-danger)' }
        if (riskScore >= 6) return { label: 'HIGH', color: 'var(--color-warning)' }
        if (riskScore >= 4) return { label: 'MEDIUM', color: 'var(--color-primary)' }
        return { label: 'LOW', color: 'var(--color-success)' }
    }

    const getPriorityBadge = (priority) => {
        const badges = {
            IMMEDIATE: { label: 'IMMEDIATE', color: 'var(--color-danger)' },
            HOUR_24: { label: '24 HOURS', color: 'var(--color-warning)' },
            HOUR_48: { label: '48 HOURS', color: 'var(--color-primary)' },
            WEEK: { label: 'THIS WEEK', color: 'var(--color-primary)' },
            MONTH: { label: 'THIS MONTH', color: 'var(--color-light-6)' }
        }
        return badges[priority] || { label: priority, color: 'var(--color-light-6)' }
    }

    const getPatternIcon = (pattern) => {
        switch (pattern) {
            case 'EXPONENTIAL':
                return <TrendingUp className={styles.patternIcon} style={{ color: 'var(--color-danger)' }} />
            case 'LINEAR':
                return <TrendingUp className={styles.patternIcon} style={{ color: 'var(--color-primary)' }} />
            case 'DECLINING':
                return <TrendingDown className={styles.patternIcon} style={{ color: 'var(--color-success)' }} />
            case 'STABLE':
                return <Activity className={styles.patternIcon} style={{ color: 'var(--color-light-6)' }} />
            default:
                return <Zap className={styles.patternIcon} style={{ color: 'var(--color-warning)' }} />
        }
    }

    const renderDeathClockView = () => (
        <div className={styles.deathClockView}>
            <div className={styles.executiveSummary}>
                <AlertTriangle size={24} className={styles.summaryIcon} />
                <div>
                    <h3>Executive Summary</h3>
                    <p>{summary?.executiveSummary || 'All resources operating within normal capacity.'}</p>
                </div>
            </div>

            <div className={styles.statsGrid}>
                <div className={styles.statCard}>
                    <Clock className={styles.statIcon} />
                    <div className={styles.statContent}>
                        <div className={styles.statValue}>{deathClock?.totalCritical || 0}</div>
                        <div className={styles.statLabel}>Critical Forecasts</div>
                    </div>
                </div>
                <div className={styles.statCard}>
                    <Target className={styles.statIcon} />
                    <div className={styles.statContent}>
                        <div className={styles.statValue}>{recommendations.length}</div>
                        <div className={styles.statLabel}>Recommendations</div>
                    </div>
                </div>
                <div className={styles.statCard}>
                    <Activity className={styles.statIcon} />
                    <div className={styles.statContent}>
                        <div className={styles.statValue}>{events.length}</div>
                        <div className={styles.statLabel}>Recent Events</div>
                    </div>
                </div>
            </div>

            {deathClock?.forecasts && deathClock.forecasts.length > 0 ? (
                <div className={styles.deathClockTable}>
                    <table>
                        <thead>
                            <tr>
                                <th>Table</th>
                                <th>Resource</th>
                                <th>Current</th>
                                <th>Exhaustion Date</th>
                                <th>Days Left</th>
                                <th>Velocity</th>
                                <th>Acceleration</th>
                                <th>Pattern</th>
                                <th>Risk</th>
                            </tr>
                        </thead>
                        <tbody>
                            {deathClock.forecasts.map((forecast) => {
                                const severity = getSeverityBadge(forecast.riskScore)
                                return (
                                    <tr key={forecast.id}>
                                        <td className={styles.tableName}>{forecast.tableName}</td>
                                        <td>{forecast.resource}</td>
                                        <td>{forecast.currentPercent?.toFixed(1)}%</td>
                                        <td>{formatDate(forecast.exhaustionDate)}</td>
                                        <td>
                                            <span
                                                className={styles.daysRemaining}
                                                style={{ color: getDaysRemainingColor(forecast.daysRemaining) }}
                                            >
                                                {forecast.daysRemaining} days
                                            </span>
                                        </td>
                                        <td>{forecast.velocity?.toFixed(2)}%/day</td>
                                        <td>
                                            <span className={styles.acceleration}>
                                                {forecast.acceleration > 0 ? '↑' : forecast.acceleration < 0 ? '↓' : '→'}
                                                {Math.abs(forecast.acceleration)?.toFixed(2)}
                                            </span>
                                        </td>
                                        <td>
                                            <div className={styles.patternCell}>
                                                {getPatternIcon(forecast.pattern)}
                                                {forecast.pattern}
                                            </div>
                                        </td>
                                        <td>
                                            <span
                                                className={styles.badge}
                                                style={{ backgroundColor: severity.color }}
                                            >
                                                {forecast.riskScore}/10
                                            </span>
                                        </td>
                                    </tr>
                                )
                            })}
                        </tbody>
                    </table>
                </div>
            ) : (
                <div className={styles.emptyState}>
                    <CheckCircle size={48} className={styles.emptyIcon} />
                    <h3>All Clear!</h3>
                    <p>No critical capacity forecasts detected. All resources are operating within safe limits.</p>
                </div>
            )}
        </div>
    )

    const renderEventsView = () => (
        <div className={styles.eventsView}>
            <div className={styles.viewHeader}>
                <h3>Database Events Timeline</h3>
                <p>Deployments, schema changes, and maintenance operations</p>
            </div>

            {events.length > 0 ? (
                <div className={styles.eventsList}>
                    {events.map((event) => (
                        <div key={event.id} className={styles.eventCard}>
                            <div className={styles.eventHeader}>
                                <div className={styles.eventType}>
                                    {event.highRiskEvent && (
                                        <AlertTriangle size={16} className={styles.riskIcon} />
                                    )}
                                    <span className={styles.eventTypeLabel}>{event.eventType}</span>
                                </div>
                                <span className={styles.eventTime}>{formatDateTime(event.eventTimestamp)}</span>
                            </div>
                            <div className={styles.eventName}>{event.eventName}</div>
                            <div className={styles.eventDescription}>{event.description}</div>
                            {event.affectedTables && event.affectedTables.length > 0 && (
                                <div className={styles.eventTables}>
                                    <Database size={14} />
                                    {event.affectedTables.join(', ')}
                                </div>
                            )}
                            {event.initiatedBy && (
                                <div className={styles.eventInitiator}>
                                    Initiated by: {event.initiatedBy}
                                </div>
                            )}
                        </div>
                    ))}
                </div>
            ) : (
                <div className={styles.emptyState}>
                    <Calendar size={48} className={styles.emptyIcon} />
                    <h3>No Events Logged</h3>
                    <p>Start logging deployments and schema changes to enable event correlation.</p>
                </div>
            )}
        </div>
    )

    const renderRecommendationsView = () => (
        <div className={styles.recommendationsView}>
            <div className={styles.viewHeader}>
                <h3>AI-Generated Recommendations</h3>
                <p>Actionable insights ranked by priority and impact</p>
            </div>

            {recommendations.length > 0 ? (
                <div className={styles.recommendationsList}>
                    {recommendations.map((rec) => {
                        const priority = getPriorityBadge(rec.priority)
                        return (
                            <div key={rec.id} className={styles.recommendationCard}>
                                <div className={styles.recHeader}>
                                    <div>
                                        <span
                                            className={styles.priorityBadge}
                                            style={{ backgroundColor: priority.color }}
                                        >
                                            {priority.label}
                                        </span>
                                        <span className={styles.recType}>{rec.recommendationType}</span>
                                    </div>
                                    <span
                                        className={styles.statusBadge}
                                        style={{
                                            backgroundColor: rec.status === 'COMPLETED' ? 'var(--color-success)' :
                                                           rec.status === 'IN_PROGRESS' ? 'var(--color-primary)' :
                                                           'var(--color-light-6)'
                                        }}
                                    >
                                        {rec.status}
                                    </span>
                                </div>
                                <h4 className={styles.recTitle}>{rec.title}</h4>
                                <p className={styles.recDescription}>{rec.description}</p>

                                {(rec.estimatedStorageSavingsBytes || rec.estimatedPerformanceImprovementPercent) && (
                                    <div className={styles.recImpact}>
                                        {rec.estimatedStorageSavingsBytes && (
                                            <div className={styles.impactItem}>
                                                <HardDrive size={14} />
                                                Saves {(rec.estimatedStorageSavingsBytes / (1024 * 1024 * 1024)).toFixed(2)} GB
                                            </div>
                                        )}
                                        {rec.estimatedPerformanceImprovementPercent && (
                                            <div className={styles.impactItem}>
                                                <Cpu size={14} />
                                                +{rec.estimatedPerformanceImprovementPercent}% performance
                                            </div>
                                        )}
                                    </div>
                                )}

                                {rec.status === 'PENDING' && (
                                    <div className={styles.recActions}>
                                        <button
                                            onClick={() => handleUpdateRecommendation(rec.id, 'IN_PROGRESS')}
                                            className={styles.actionButton}
                                        >
                                            <Activity size={14} /> Start Implementation
                                        </button>
                                        <button
                                            onClick={() => handleUpdateRecommendation(rec.id, 'COMPLETED')}
                                            className={styles.actionButtonSuccess}
                                        >
                                            <CheckCircle size={14} /> Mark Complete
                                        </button>
                                        <button
                                            onClick={() => handleUpdateRecommendation(rec.id, 'DISMISSED')}
                                            className={styles.actionButtonDanger}
                                        >
                                            <XCircle size={14} /> Dismiss
                                        </button>
                                    </div>
                                )}
                            </div>
                        )
                    })}
                </div>
            ) : (
                <div className={styles.emptyState}>
                    <Target size={48} className={styles.emptyIcon} />
                    <h3>No Recommendations</h3>
                    <p>Sentinel will generate recommendations when capacity issues are detected.</p>
                </div>
            )}
        </div>
    )

    return (
        <div className={styles.container}>
            <div className={styles.header}>
                <div className={styles.titleSection}>
                    <h2>Sentinel-DBA Analytics</h2>
                    <p>Predictive capacity forecasting and event correlation</p>
                </div>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <button
                        onClick={() => setShowSetupWizard(true)}
                        className={styles.setupButton}
                        style={{
                            backgroundColor: hasResourceLimits ? 'var(--color-primary)' : 'var(--color-warning)',
                            color: 'white',
                            padding: '0.5rem 1rem',
                            borderRadius: '0.5rem',
                            border: 'none',
                            cursor: 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '0.5rem',
                            fontSize: '0.875rem',
                            fontWeight: '500'
                        }}
                    >
                        <Settings size={16} />
                        {hasResourceLimits ? 'Edit Resource Limits' : 'Setup Resource Limits'}
                    </button>
                    <button
                        onClick={handleTriggerSnapshot}
                        disabled={snapshotLoading}
                        style={{
                            backgroundColor: 'var(--color-primary)',
                            color: 'white',
                            padding: '0.5rem 1rem',
                            borderRadius: '0.5rem',
                            border: 'none',
                            cursor: snapshotLoading ? 'not-allowed' : 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '0.5rem',
                            fontSize: '0.875rem',
                            fontWeight: '500',
                            opacity: snapshotLoading ? 0.5 : 1
                        }}
                    >
                        <Play size={16} className={snapshotLoading ? styles.spinning : ''} />
                        {snapshotLoading ? 'Capturing...' : 'Trigger Snapshot'}
                    </button>
                    <button
                        onClick={loadData}
                        disabled={loading}
                        className={styles.refreshButton}
                    >
                        <RefreshCw size={14} className={loading ? styles.spinning : ''} />
                    </button>
                </div>
            </div>

            <div className={styles.tabs}>
                <button
                    className={activeView === 'deathClock' ? styles.tabActive : styles.tab}
                    onClick={() => setActiveView('deathClock')}
                >
                    <Clock size={16} />
                    Death Clock
                </button>
                <button
                    className={activeView === 'events' ? styles.tabActive : styles.tab}
                    onClick={() => setActiveView('events')}
                >
                    <Calendar size={16} />
                    Events ({events.length})
                </button>
                <button
                    className={activeView === 'recommendations' ? styles.tabActive : styles.tab}
                    onClick={() => setActiveView('recommendations')}
                >
                    <Target size={16} />
                    Recommendations ({recommendations.length})
                </button>
            </div>

            {error && (
                <div className={styles.error}>
                    <AlertTriangle size={16} />
                    {error}
                </div>
            )}

            <div className={styles.content}>
                {loading ? (
                    <div className={styles.loading}>
                        <RefreshCw size={32} className={styles.spinning} />
                        <p>Loading Sentinel analytics...</p>
                    </div>
                ) : (
                    <>
                        {activeView === 'deathClock' && renderDeathClockView()}
                        {activeView === 'events' && renderEventsView()}
                        {activeView === 'recommendations' && renderRecommendationsView()}
                    </>
                )}
            </div>

            {/* Resource Limits Setup Wizard */}
            {showSetupWizard && (
                <ResourceLimitsSetupWizard
                    connectionId={connectionId}
                    onClose={() => setShowSetupWizard(false)}
                    onComplete={() => {
                        checkResourceLimits()
                        loadData()
                    }}
                />
            )}
        </div>
    )
}
