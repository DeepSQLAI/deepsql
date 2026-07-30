'use client'

import { useState, useMemo } from 'react'
import {
    LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
    ResponsiveContainer, AreaChart, Area
} from 'recharts'
import {
    TrendingDown, TrendingUp, Clock, AlertTriangle, CheckCircle,
    RefreshCw, Calendar, Activity, AlertCircle, ArrowLeft
} from 'lucide-react'
import {
    useTrackedQueries,
    usePerformanceRegressions,
    usePerformanceTrend,
    useAcknowledgeRegression,
    useResolveRegression,
} from '@/lib/hooks/queries'
import styles from './QueryPerformanceTab.module.css'

export default function QueryPerformanceTab({ connectionId }) {
    // UI state only
    const [activeView, setActiveView] = useState('queries')
    const [timeRange, setTimeRange] = useState(7)
    const [selectedQuery, setSelectedQuery] = useState(null)

    // Server state with TanStack Query
    const {
        data: queriesData,
        isLoading: loadingQueries,
        error: queriesError,
        refetch: refetchQueries,
    } = useTrackedQueries(connectionId)

    const {
        data: regressionsData,
        isLoading: loadingRegressions,
        refetch: refetchRegressions,
    } = usePerformanceRegressions(connectionId, false)

    const {
        data: trendData,
    } = usePerformanceTrend(
        connectionId,
        selectedQuery?.queryHash,
        timeRange
    )

    // Mutations
    const acknowledgeRegressionMutation = useAcknowledgeRegression()
    const resolveRegressionMutation = useResolveRegression()

    // Derived state
    const queries = useMemo(() => queriesData?.queries || [], [queriesData])
    const regressions = useMemo(() => regressionsData?.regressions || [], [regressionsData])
    const loading = loadingQueries || loadingRegressions
    const error = queriesError?.message || null

    const loadData = () => {
        refetchQueries()
        refetchRegressions()
    }

    const handleSelectQuery = (query) => {
        setSelectedQuery(query)
        setActiveView('trends')
    }

    const handleAcknowledgeRegression = (regressionId) => {
        acknowledgeRegressionMutation.mutate({ regressionId, acknowledgedBy: 'user' })
    }

    const handleResolveRegression = (regressionId) => {
        const notes = prompt('Enter resolution notes:')
        if (notes) {
            resolveRegressionMutation.mutate({ regressionId, resolutionNotes: notes })
        }
    }

    const formatTimestamp = (timestamp) => {
        const date = new Date(timestamp)
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], {
            hour: '2-digit',
            minute: '2-digit'
        })
    }

    const formatDuration = (ms) => {
        if (ms < 1) return `${(ms * 1000).toFixed(2)}μs`
        if (ms < 1000) return `${ms.toFixed(2)}ms`
        return `${(ms / 1000).toFixed(2)}s`
    }

    const getSeverityClass = (severity) => {
        switch (severity) {
            case 'CRITICAL': return styles.severityCritical
            case 'SEVERE': return styles.severitySevere
            case 'MODERATE': return styles.severityModerate
            case 'MINOR': return styles.severityMinor
            default: return ''
        }
    }

    const getPerformanceClass = (changePercent) => {
        if (!changePercent) return ''
        if (changePercent > 50) return styles.performanceRegression
        if (changePercent < -20) return styles.performanceImprovement
        return ''
    }

    // Render Queries List View
    const renderQueries = () => {
        const activeRegressionCount = regressions.filter(r => !r.resolved).length
        const criticalRegressionCount = regressions.filter(
            r => !r.resolved && r.severity === 'CRITICAL'
        ).length

        return (
            <div className={styles.queriesView}>
                {/* Summary Cards */}
                <div className={styles.summaryCards}>
                    <div className={styles.card}>
                        <div className={styles.cardIcon} style={{ color: '#525252' }}>
                            <Activity size={32} />
                        </div>
                        <div className={styles.cardContent}>
                            <div className={styles.cardLabel}>Tracked Queries</div>
                            <div className={styles.cardValue}>{queries.length}</div>
                        </div>
                    </div>

                    <div className={styles.card}>
                        <div className={styles.cardIcon} style={{
                            color: activeRegressionCount > 0 ? 'var(--color-danger)' : 'var(--color-primary)'
                        }}>
                            <AlertTriangle size={32} />
                        </div>
                        <div className={styles.cardContent}>
                            <div className={styles.cardLabel}>Active Regressions</div>
                            <div className={styles.cardValue} style={{
                                color: activeRegressionCount > 0 ? 'var(--color-danger)' : 'var(--color-primary)'
                            }}>
                                {activeRegressionCount}
                            </div>
                            {criticalRegressionCount > 0 && (
                                <div className={styles.cardSubtext}>
                                    {criticalRegressionCount} Critical
                                </div>
                            )}
                        </div>
                    </div>

                    <div className={styles.card}>
                        <div className={styles.cardIcon} style={{ color: '#525252' }}>
                            <Clock size={32} />
                        </div>
                        <div className={styles.cardContent}>
                            <div className={styles.cardLabel}>Time Range</div>
                            <div className={styles.cardValue}>{timeRange} days</div>
                        </div>
                    </div>
                </div>

                {/* Queries List */}
                <div className={styles.queriesList}>
                    {queries.map((query, idx) => (
                        <div
                            key={idx}
                            className={`${styles.queryCard} ${query.hasActiveRegression ? styles.hasRegression : ''}`}
                            onClick={() => handleSelectQuery(query)}
                        >
                            <div className={styles.queryHeader}>
                                <div className={styles.queryPreview}>
                                    {query.normalizedQuery?.substring(0, 150)}...
                                </div>
                                {query.hasActiveRegression && (
                                    <div className={`${styles.regressionBadge} ${getSeverityClass(query.regressionSeverity)}`}>
                                        <AlertTriangle size={14} />
                                        {query.regressionSeverity}
                                    </div>
                                )}
                            </div>

                            <div className={styles.queryMetrics}>
                                <div className={styles.metric}>
                                    <span className={styles.metricLabel}>Latest:</span>
                                    <span className={styles.metricValue}>
                                        {formatDuration(query.latestExecutionTime)}
                                    </span>
                                </div>
                                <div className={styles.metric}>
                                    <span className={styles.metricLabel}>Avg (10):</span>
                                    <span className={styles.metricValue}>
                                        {formatDuration(query.recentAvgExecutionTime)}
                                    </span>
                                </div>
                                {query.baselineAvg && (
                                    <div className={styles.metric}>
                                        <span className={styles.metricLabel}>Baseline:</span>
                                        <span className={styles.metricValue}>
                                            {formatDuration(query.baselineAvg)}
                                        </span>
                                    </div>
                                )}
                                {query.performanceChange && (
                                    <div className={`${styles.metric} ${getPerformanceClass(query.performanceChange)}`}>
                                        {query.performanceChange > 0 ? (
                                            <TrendingUp size={16} />
                                        ) : (
                                            <TrendingDown size={16} />
                                        )}
                                        <span className={styles.metricValue}>
                                            {query.performanceChange > 0 ? '+' : ''}
                                            {query.performanceChange.toFixed(1)}%
                                        </span>
                                    </div>
                                )}
                                <div className={styles.metric}>
                                    <span className={styles.metricLabel}>Executions:</span>
                                    <span className={styles.metricValue}>
                                        {query.executionCount}
                                    </span>
                                </div>
                            </div>

                            <div className={styles.queryFooter}>
                                <span className={styles.timestamp}>
                                    Last seen: {formatTimestamp(query.lastSeen)}
                                </span>
                            </div>
                        </div>
                    ))}

                    {queries.length === 0 && (
                        <div className={styles.emptyState}>
                            <Activity size={48} color="var(--color-light-6)" />
                            <h3>No Query Data</h3>
                            <p>Execute queries to start tracking performance</p>
                        </div>
                    )}
                </div>
            </div>
        )
    }

    // Render Performance Trend View
    const renderTrends = () => {
        if (!selectedQuery || !trendData) {
            return (
                <div className={styles.emptyState}>
                    <TrendingUp size={48} color="var(--color-light-6)" />
                    <h3>No Query Selected</h3>
                    <p>Select a query from the list to view performance trends</p>
                </div>
            )
        }

        const chartData = trendData.trendData?.map(d => ({
            timestamp: new Date(d.timestamp).toLocaleString([], {
                month: 'short',
                day: 'numeric',
                hour: '2-digit'
            }),
            avg: d.avgExecutionTime,
            max: d.maxExecutionTime,
            min: d.minExecutionTime
        })) || []

        return (
            <div className={styles.trendsView}>
                <div className={styles.trendHeader}>
                    <div>
                        <h3>Performance Trend</h3>
                        <div className={styles.queryText}>{trendData.sampleQuery}</div>
                    </div>
                    <button onClick={() => setActiveView('queries')} className={styles.backButton} title="Back to Queries">
                        <ArrowLeft size={14} />
                    </button>
                </div>

                <div className={styles.trendStats}>
                    <div className={styles.statBox}>
                        <div className={styles.statLabel}>Total Executions</div>
                        <div className={styles.statValue}>{trendData.totalExecutions}</div>
                    </div>
                    {selectedQuery.baselineAvg && (
                        <div className={styles.statBox}>
                            <div className={styles.statLabel}>Baseline Avg</div>
                            <div className={styles.statValue}>
                                {formatDuration(selectedQuery.baselineAvg)}
                            </div>
                        </div>
                    )}
                    {selectedQuery.p95 && (
                        <div className={styles.statBox}>
                            <div className={styles.statLabel}>P95</div>
                            <div className={styles.statValue}>
                                {formatDuration(selectedQuery.p95)}
                            </div>
                        </div>
                    )}
                    {selectedQuery.p99 && (
                        <div className={styles.statBox}>
                            <div className={styles.statLabel}>P99</div>
                            <div className={styles.statValue}>
                                {formatDuration(selectedQuery.p99)}
                            </div>
                        </div>
                    )}
                </div>

                <div className={styles.chartContainer}>
                    <ResponsiveContainer width="100%" height={400}>
                        <AreaChart data={chartData}>
                            <CartesianGrid strokeDasharray="3 3" stroke="var(--color-light-3)" />
                            <XAxis
                                dataKey="timestamp"
                                tick={{ fill: 'var(--color-light-6)', fontSize: 12 }}
                            />
                            <YAxis
                                label={{ value: 'Execution Time (ms)', angle: -90, position: 'insideLeft' }}
                                tick={{ fill: 'var(--color-light-6)', fontSize: 12 }}
                            />
                            <Tooltip
                                contentStyle={{
                                    backgroundColor: 'var(--color-white)',
                                    border: '1px solid var(--color-light-3)',
                                    borderRadius: '6px'
                                }}
                                formatter={(value) => formatDuration(value)}
                            />
                            <Legend />
                            <Area
                                type="monotone"
                                dataKey="max"
                                stroke="var(--color-danger)"
                                fill="var(--color-danger-soft)"
                                name="Max"
                            />
                            <Area
                                type="monotone"
                                dataKey="avg"
                                stroke="var(--color-primary)"
                                fill="var(--color-primary-soft)"
                                name="Average"
                            />
                            <Area
                                type="monotone"
                                dataKey="min"
                                stroke="var(--color-primary-dark)"
                                fill="var(--color-primary-light)"
                                name="Min"
                            />
                        </AreaChart>
                    </ResponsiveContainer>
                </div>
            </div>
        )
    }

    // Render Regressions View
    const renderRegressions = () => {
        const activeRegressions = regressions.filter(r => !r.resolved)
        const resolvedRegressions = regressions.filter(r => r.resolved)

        return (
            <div className={styles.regressionsView}>
                <div className={styles.section}>
                    <h3>Active Regressions ({activeRegressions.length})</h3>
                    {activeRegressions.map(regression => (
                        <div key={regression.id} className={`${styles.regressionCard} ${getSeverityClass(regression.severity)}`}>
                            <div className={styles.regressionHeader}>
                                <div>
                                    <span className={`${styles.severityBadge} ${getSeverityClass(regression.severity)}`}>
                                        {regression.severity}
                                    </span>
                                    <span className={styles.regressionType}>
                                        {regression.regressionType}
                                    </span>
                                </div>
                                <div className={styles.regressionActions}>
                                    {!regression.acknowledged && (
                                        <button
                                            onClick={() => handleAcknowledgeRegression(regression.id)}
                                            className={styles.acknowledgeButton}
                                        >
                                            <CheckCircle size={16} />
                                            Acknowledge
                                        </button>
                                    )}
                                    <button
                                        onClick={() => handleResolveRegression(regression.id)}
                                        className={styles.resolveButton}
                                    >
                                        Resolve
                                    </button>
                                </div>
                            </div>

                            <div className={styles.regressionQuery}>
                                {regression.normalizedQuery?.substring(0, 200)}...
                            </div>

                            <div className={styles.regressionMetrics}>
                                <div className={styles.metric}>
                                    <span className={styles.metricLabel}>Baseline:</span>
                                    <span className={styles.metricValue}>
                                        {formatDuration(regression.baselineAvgMs)}
                                    </span>
                                </div>
                                <div className={styles.metric}>
                                    <span className={styles.metricLabel}>Current:</span>
                                    <span className={styles.metricValue}>
                                        {formatDuration(regression.currentAvgMs)}
                                    </span>
                                </div>
                                <div className={`${styles.metric} ${styles.performanceRegression}`}>
                                    <TrendingUp size={16} />
                                    <span className={styles.metricValue}>
                                        +{regression.slowdownPercent.toFixed(0)}% slower
                                    </span>
                                </div>
                                <div className={styles.metric}>
                                    <span className={styles.metricLabel}>Factor:</span>
                                    <span className={styles.metricValue}>
                                        {regression.slowdownFactor.toFixed(1)}x
                                    </span>
                                </div>
                            </div>

                            <div className={styles.regressionFooter}>
                                <span>Detected: {formatTimestamp(regression.detectedAt)}</span>
                                {regression.acknowledged && (
                                    <span>Acknowledged by {regression.acknowledgedBy}</span>
                                )}
                            </div>
                        </div>
                    ))}

                    {activeRegressions.length === 0 && (
                        <div className={styles.emptyState}>
                            <CheckCircle size={48} color="var(--color-success)" />
                            <h3>No Active Regressions</h3>
                            <p>All queries are performing well!</p>
                        </div>
                    )}
                </div>

                {resolvedRegressions.length > 0 && (
                    <div className={styles.section}>
                        <h3>Resolved Regressions ({resolvedRegressions.length})</h3>
                        {resolvedRegressions.slice(0, 5).map(regression => (
                            <div key={regression.id} className={styles.resolvedRegressionCard}>
                                <div className={styles.regressionQuery}>
                                    {regression.normalizedQuery?.substring(0, 100)}...
                                </div>
                                <div className={styles.resolvedInfo}>
                                    <span>Resolved: {formatTimestamp(regression.resolvedAt)}</span>
                                    {regression.resolutionNotes && (
                                        <span>Notes: {regression.resolutionNotes}</span>
                                    )}
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </div>
        )
    }

    return (
        <div className={styles.container}>
            {/* Header */}
            <div className={styles.header}>
                <div>
                    <h1>Query Performance History</h1>
                    <p className={styles.subtitle}>Track query performance trends and detect regressions</p>
                </div>
                <div className={styles.headerControls}>
                    <div className={styles.timeRangeSelector}>
                        <Calendar size={16} />
                        <select value={timeRange} onChange={(e) => setTimeRange(Number(e.target.value))}>
                            <option value={1}>Last 24 hours</option>
                            <option value={7}>Last 7 days</option>
                            <option value={14}>Last 14 days</option>
                            <option value={30}>Last 30 days</option>
                        </select>
                    </div>

                    <button onClick={loadData} className={styles.refreshButton} title="Refresh">
                        <RefreshCw size={14} />
                    </button>
                </div>
            </div>

            {/* View Tabs */}
            <div className={styles.viewTabs}>
                <button
                    className={activeView === 'queries' ? styles.activeTab : ''}
                    onClick={() => setActiveView('queries')}
                >
                    <Activity size={16} />
                    Tracked Queries
                </button>
                <button
                    className={activeView === 'trends' ? styles.activeTab : ''}
                    onClick={() => setActiveView('trends')}
                >
                    <TrendingUp size={16} />
                    Performance Trends
                </button>
                <button
                    className={activeView === 'regressions' ? styles.activeTab : ''}
                    onClick={() => setActiveView('regressions')}
                >
                    <AlertTriangle size={16} />
                    Regressions
                    {regressions.filter(r => !r.resolved).length > 0 && (
                        <span className={styles.badge}>
                            {regressions.filter(r => !r.resolved).length}
                        </span>
                    )}
                </button>
            </div>

            {/* Content */}
            {loading && (
                <div className={styles.loadingState}>
                    <RefreshCw className={styles.spinner} size={48} />
                    <p>Loading performance data...</p>
                </div>
            )}

            {error && (
                <div className={styles.errorState}>
                    <AlertCircle size={48} color="var(--color-danger)" />
                    <h3>Error Loading Data</h3>
                    <p>{error}</p>
                </div>
            )}

            {!loading && !error && (
                <div className={styles.content}>
                    {activeView === 'queries' && renderQueries()}
                    {activeView === 'trends' && renderTrends()}
                    {activeView === 'regressions' && renderRegressions()}
                </div>
            )}
        </div>
    )
}
