'use client'

import { useState, useEffect } from 'react'
import { LineChart, Line, BarChart, Bar, PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts'
import { TrendingUp, TrendingDown, Activity, AlertCircle, CheckCircle, Loader2, Calendar } from 'lucide-react'
import { performanceAPI } from '@/lib/api/client'
import styles from './PerformanceDashboard.module.css'

export default function PerformanceDashboard({ connectionId }) {
    const [loading, setLoading] = useState(false)
    const [data, setData] = useState(null)
    const [error, setError] = useState(null)
    const [timeRange, setTimeRange] = useState(30)

    useEffect(() => {
        if (connectionId) {
            fetchDashboardData()
        }
    }, [connectionId, timeRange])

    const fetchDashboardData = async () => {
        setLoading(true)
        setError(null)
        try {
            const dashboardData = await performanceAPI.getDashboardData(connectionId, timeRange)
            setData(dashboardData)
        } catch (err) {
            setError(err.message)
        } finally {
            setLoading(false)
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

    const formatTimestamp = (timestamp) => {
        const date = new Date(timestamp)
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})
    }

    const HEALTH_COLORS = {
        'EXCELLENT': 'var(--color-success)',
        'GOOD': 'var(--color-primary)',
        'FAIR': 'var(--color-warning)',
        'POOR': 'var(--color-danger)',
        'CRITICAL': 'var(--color-danger)'
    }

    if (loading && !data) {
        return (
            <div className={styles.container}>
                <div className={styles.loadingState}>
                    <Loader2 className={styles.spinner} size={48} />
                    <p>Loading dashboard data...</p>
                </div>
            </div>
        )
    }

    if (error) {
        return (
            <div className={styles.container}>
                <div className={styles.errorState}>
                    <AlertCircle size={48} color="var(--color-danger)" />
                    <h3>Error Loading Dashboard</h3>
                    <p>{error}</p>
                    <button onClick={fetchDashboardData} className={styles.retryButton}>
                        Retry
                    </button>
                </div>
            </div>
        )
    }

    if (!data) {
        return (
            <div className={styles.container}>
                <div className={styles.emptyState}>
                    <Activity size={48} color="var(--color-light-5)" />
                    <h3>No Data Available</h3>
                    <p>Run some analyses to see performance trends</p>
                </div>
            </div>
        )
    }

    // Prepare health distribution data for pie chart
    const healthDistData = data.healthDistribution ? Object.entries(data.healthDistribution).map(([key, value]) => ({
        name: key,
        value: value
    })) : []

    return (
        <div className={styles.container}>
            {/* Header */}
            <div className={styles.header}>
                <div>
                    <h1>Performance Dashboard</h1>
                    <p className={styles.subtitle}>Database performance metrics and trends</p>
                </div>
                <div className={styles.timeRangeSelector}>
                    <Calendar size={16} />
                    <select value={timeRange} onChange={(e) => setTimeRange(Number(e.target.value))}>
                        <option value={7}>Last 7 days</option>
                        <option value={14}>Last 14 days</option>
                        <option value={30}>Last 30 days</option>
                        <option value={60}>Last 60 days</option>
                        <option value={90}>Last 90 days</option>
                    </select>
                </div>
            </div>

            {/* Summary Cards */}
            <div className={styles.summaryCards}>
                <div className={styles.card} style={{ borderColor: getHealthColor(data.overallHealth) }}>
                    <div className={styles.cardIcon} style={{ color: getHealthColor(data.overallHealth) }}>
                        {data.overallHealth === 'EXCELLENT' || data.overallHealth === 'GOOD' ?
                            <CheckCircle size={32} /> : <AlertCircle size={32} />}
                    </div>
                    <div className={styles.cardContent}>
                        <div className={styles.cardLabel}>Overall Health</div>
                        <div className={styles.cardValue} style={{ color: getHealthColor(data.overallHealth) }}>
                            {data.overallHealth || 'UNKNOWN'}
                        </div>
                    </div>
                </div>

                <div className={styles.card}>
                    <div className={styles.cardIcon} style={{ color: 'var(--color-primary)' }}>
                        <Activity size={32} />
                    </div>
                    <div className={styles.cardContent}>
                        <div className={styles.cardLabel}>EXPLAIN Analyses</div>
                        <div className={styles.cardValue}>{data.totalExplainAnalyses || 0}</div>
                        <div className={styles.cardSubtext}>
                            Avg Score: {data.explainIssuesSummary?.avgScore || 0}
                        </div>
                    </div>
                </div>

                <div className={styles.card}>
                    <div className={styles.cardIcon} style={{ color: 'var(--color-warning)' }}>
                        <TrendingDown size={32} />
                    </div>
                    <div className={styles.cardContent}>
                        <div className={styles.cardLabel}>Slow Query Analyses</div>
                        <div className={styles.cardValue}>{data.totalSlowQueryAnalyses || 0}</div>
                        <div className={styles.cardSubtext}>
                            {data.healthDistribution ? Object.values(data.healthDistribution).reduce((a, b) => a + b, 0) : 0} health checks
                        </div>
                    </div>
                </div>

                <div className={styles.card}>
                    <div className={styles.cardIcon} style={{ color: 'var(--color-danger)' }}>
                        <AlertCircle size={32} />
                    </div>
                    <div className={styles.cardContent}>
                        <div className={styles.cardLabel}>Top Issues</div>
                        <div className={styles.cardValue}>{data.topIssues?.length || 0}</div>
                        <div className={styles.cardSubtext}>Requires attention</div>
                    </div>
                </div>
            </div>

            {/* Charts Row */}
            <div className={styles.chartsRow}>
                {/* EXPLAIN Score Trend */}
                {data.explainScoreTrend && data.explainScoreTrend.length > 0 && (
                    <div className={styles.chartCard}>
                        <h3>EXPLAIN Plan Performance Score Trend</h3>
                        <ResponsiveContainer width="100%" height={300}>
                            <LineChart data={data.explainScoreTrend}>
                                <CartesianGrid strokeDasharray="3 3" />
                                <XAxis
                                    dataKey="timestamp"
                                    tickFormatter={(val) => new Date(val).toLocaleDateString()}
                                    angle={-45}
                                    textAnchor="end"
                                    height={80}
                                />
                                <YAxis domain={[0, 100]} />
                                <Tooltip
                                    labelFormatter={(val) => formatTimestamp(val)}
                                    formatter={(value) => [Math.round(value), 'Score']}
                                />
                                <Legend />
                                <Line
                                    type="monotone"
                                    dataKey="value"
                                    stroke="var(--color-primary)"
                                    strokeWidth={2}
                                    name="Performance Score"
                                    dot={{ fill: 'var(--color-primary)', r: 4 }}
                                />
                            </LineChart>
                        </ResponsiveContainer>
                    </div>
                )}

                {/* Health Distribution */}
                {healthDistData.length > 0 && (
                    <div className={styles.chartCard}>
                        <h3>Slow Query Health Distribution</h3>
                        <ResponsiveContainer width="100%" height={300}>
                            <PieChart>
                                <Pie
                                    data={healthDistData}
                                    cx="50%"
                                    cy="50%"
                                    labelLine={false}
                                    label={({ name, percent }) => `${name} (${(percent * 100).toFixed(0)}%)`}
                                    outerRadius={80}
                                    fill="var(--color-light-6)"
                                    dataKey="value"
                                >
                                    {healthDistData.map((entry, index) => (
                                        <Cell key={`cell-${index}`} fill={HEALTH_COLORS[entry.name] || 'var(--color-light-5)'} />
                                    ))}
                                </Pie>
                                <Tooltip />
                            </PieChart>
                        </ResponsiveContainer>
                    </div>
                )}
            </div>

            {/* Slow Query Trend */}
            {data.slowQueryTrend && data.slowQueryTrend.length > 0 && (
                <div className={styles.chartCardFull}>
                    <h3>Slow Query Count Over Time</h3>
                    <ResponsiveContainer width="100%" height={300}>
                        <BarChart data={data.slowQueryTrend}>
                            <CartesianGrid strokeDasharray="3 3" />
                            <XAxis
                                dataKey="timestamp"
                                tickFormatter={(val) => new Date(val).toLocaleDateString()}
                                angle={-45}
                                textAnchor="end"
                                height={80}
                            />
                            <YAxis />
                            <Tooltip
                                labelFormatter={(val) => formatTimestamp(val)}
                                formatter={(value) => [value, 'Slow Queries']}
                            />
                            <Legend />
                            <Bar dataKey="value" fill="var(--color-warning)" name="Slow Queries" />
                        </BarChart>
                    </ResponsiveContainer>
                </div>
            )}

            {/* Top Issues */}
            {data.topIssues && data.topIssues.length > 0 && (
                <div className={styles.issuesCard}>
                    <h3>Top Issues Requiring Attention</h3>
                    <div className={styles.issuesList}>
                        {data.topIssues.map((issue, index) => (
                            <div key={index} className={styles.issueItem}>
                                <div className={styles.issueIcon}>
                                    <AlertCircle size={20} color={issue.type === 'SLOW_QUERY' ? 'var(--color-warning)' : 'var(--color-primary)'} />
                                </div>
                                <div className={styles.issueContent}>
                                    <div className={styles.issueType}>{issue.type.replace('_', ' ')}</div>
                                    <div className={styles.issueDescription}>{issue.description}</div>
                                    <div className={styles.issueTimestamp}>{formatTimestamp(issue.timestamp)}</div>
                                </div>
                                {issue.severity > 0 && (
                                    <div className={styles.issueSeverity}>
                                        Score: {issue.severity}
                                    </div>
                                )}
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    )
}
