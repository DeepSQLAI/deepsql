'use client'

import { useState, useMemo } from 'react'
import { AlertCircle, RefreshCw, Filter, Zap, Settings, Layers, Wrench, HelpCircle } from 'lucide-react'
import { HelpTooltip } from '@/components/tabs/Brain/components/HelpTooltip'
import {
    usePerformanceSnapshots,
    usePerformanceSummary,
    useTableUsage,
    usePerformanceActions,
    usePerformanceActionsSummary,
    useRefreshPerformanceActions,
    useUpdateActionStatus,
} from '@/lib/hooks/queries'
import {
    CompactStatCard,
    TableHeatmap,
    PerformanceActionCard,
} from './components'
import styles from './PerformanceInsightsTab.module.css'

const CATEGORY_OPTIONS = [
    { value: 'all', label: 'All Categories' },
    { value: 'INDEX', label: 'Index', icon: Zap },
    { value: 'QUERY_REWRITE', label: 'Query', icon: RefreshCw },
    { value: 'CONFIG', label: 'Config', icon: Settings },
    { value: 'SCHEMA', label: 'Schema', icon: Layers },
    { value: 'MAINTENANCE', label: 'Maintenance', icon: Wrench },
]

const SORT_OPTIONS = [
    { value: 'roi', label: 'ROI (High → Low)' },
    { value: 'impact', label: 'Impact (High → Low)' },
    { value: 'effort', label: 'Effort (Low → High)' },
]

const AVG_IMPACT_HELP = {
    title: 'Estimated Speedup',
    description: 'This value is the average impact score (0-100) across pending actions.',
    impact: 'Impact scores are heuristic estimates of performance improvement, not measured speedups.',
    examples: 'Example: 37% means pending actions average ~37/100 impact.',
}

export default function PerformanceInsightsTab({ connectionId }) {
    // UI state
    const [timeRange] = useState(1)
    const [categoryFilter, setCategoryFilter] = useState('all')
    const [sortBy, setSortBy] = useState('roi')
    const [showDismissed, setShowDismissed] = useState(false)

    // Server state with TanStack Query
    const { error: snapshotsError } = usePerformanceSnapshots(connectionId, timeRange)
    const { data: summaryData } = usePerformanceSummary(connectionId, timeRange)
    const { data: tableUsageData } = useTableUsage(connectionId)

    // Performance Actions
    const { data: actions, isLoading: actionsLoading, refetch: refetchActions } = usePerformanceActions(connectionId)
    const { data: actionsSummary } = usePerformanceActionsSummary(connectionId)
    const refreshActions = useRefreshPerformanceActions()
    const updateStatus = useUpdateActionStatus()

    // Derived state
    const summary = useMemo(() => summaryData?.summary || null, [summaryData])
    const tableUsage = useMemo(() => tableUsageData?.tables || [], [tableUsageData])
    const error = snapshotsError?.message || null

    // Filter and sort actions
    const filteredActions = useMemo(() => {
        if (!actions) return []

        let result = [...actions]

        // Filter by category
        if (categoryFilter !== 'all') {
            result = result.filter(a => a.category === categoryFilter)
        }

        // Filter by status
        if (!showDismissed) {
            result = result.filter(a => a.status !== 'DISMISSED')
        }

        // Sort
        if (sortBy === 'roi') {
            result.sort((a, b) => b.roi - a.roi)
        } else if (sortBy === 'impact') {
            result.sort((a, b) => b.impactScore - a.impactScore)
        } else if (sortBy === 'effort') {
            result.sort((a, b) => a.effortScore - b.effortScore)
        }

        return result
    }, [actions, categoryFilter, sortBy, showDismissed])

    const formatNumber = (num) => {
        if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`
        if (num >= 1000) return `${(num / 1000).toFixed(1)}K`
        return num?.toString() || '0'
    }

    // Calculate stats
    const pendingCount = actionsSummary?.pendingCount || 0
    const topRoi = actionsSummary?.topRoi || 0
    const avgImpact = actionsSummary?.avgImpact || 0
    const byCategory = actionsSummary?.byCategory || {}

    // Handle refresh
    const handleRefresh = async () => {
        await refreshActions.mutateAsync(connectionId)
        refetchActions()
    }

    // Handle dismiss
    const handleDismiss = async (action) => {
        await updateStatus.mutateAsync({
            actionId: action.id,
            status: 'DISMISSED',
            resolvedBy: 'user',
            notes: null,
        })
    }

    return (
        <div className={styles.container}>
            {error && (
                <div className={styles.error}>
                    <AlertCircle size={16} />
                    <span>{error}</span>
                </div>
            )}

            {/* Hero Section */}
            {connectionId && (
                <div className={styles.heroSection}>
                    <h1 className={styles.heroTitle}>
                        Your database can be {Math.round(avgImpact)}% faster.
                        <HelpTooltip content={AVG_IMPACT_HELP}>
                            <HelpCircle size={14} className={styles.heroHelpIcon} />
                        </HelpTooltip>
                    </h1>
                    <p className={styles.heroSubtitle}>
                        {pendingCount} actions identified · {formatNumber(summary?.totalQueries || 0)} queries analyzed
                    </p>
                </div>
            )}

            {/* Stats Cards Row */}
            {connectionId && (
                <div className={styles.statsGrid}>
                    <CompactStatCard
                        title="Top Action ROI"
                        value={`${Math.round(topRoi)}%`}
                        subtitle="Best value action"
                        subtitleColor="green"
                        variant="success"
                    />
                    <CompactStatCard
                        title="Pending Actions"
                        value={pendingCount}
                        subtitle="Ready to implement"
                        variant={pendingCount > 5 ? 'warning' : 'default'}
                    />
                    <div className={styles.statCardCustom}>
                        <div className={styles.statLabel}>Actions by Category</div>
                        <div className={styles.categoryBreakdown}>
                            {Object.entries(byCategory).map(([cat, count]) => (
                                <div key={cat} className={styles.categoryItem}>
                                    <span className={styles.categoryName}>{cat}</span>
                                    <span className={styles.categoryCount}>{count}</span>
                                </div>
                            ))}
                            {Object.keys(byCategory).length === 0 && (
                                <span className={styles.noData}>No data</span>
                            )}
                        </div>
                    </div>
                    <CompactStatCard
                        title="Avg Impact"
                        value={`${Math.round(avgImpact)}%`}
                        subtitle="Performance improvement"
                    />
                </div>
            )}

            {/* Filter Bar */}
            {connectionId && (
                <div className={styles.filterBar}>
                    <div className={styles.filterGroup}>
                        <Filter size={14} />
                        <select
                            value={categoryFilter}
                            onChange={(e) => setCategoryFilter(e.target.value)}
                            className={styles.filterSelect}
                        >
                            {CATEGORY_OPTIONS.map(opt => (
                                <option key={opt.value} value={opt.value}>{opt.label}</option>
                            ))}
                        </select>
                        <select
                            value={sortBy}
                            onChange={(e) => setSortBy(e.target.value)}
                            className={styles.filterSelect}
                        >
                            {SORT_OPTIONS.map(opt => (
                                <option key={opt.value} value={opt.value}>{opt.label}</option>
                            ))}
                        </select>
                        <label className={styles.checkboxLabel}>
                            <input
                                type="checkbox"
                                checked={showDismissed}
                                onChange={(e) => setShowDismissed(e.target.checked)}
                            />
                            Show dismissed
                        </label>
                    </div>
                    <button
                        className={styles.refreshButton}
                        onClick={handleRefresh}
                        disabled={refreshActions.isPending}
                    >
                        <RefreshCw size={14} className={refreshActions.isPending ? styles.spinning : ''} />
                        {refreshActions.isPending ? 'Refreshing...' : 'Refresh Actions'}
                    </button>
                </div>
            )}

            {/* Two Column Layout */}
            <div className={styles.twoColumnLayout}>
                {/* Left Column: Performance Actions */}
                <div className={styles.leftColumn}>
                    <h2 className={styles.sectionTitle}>Performance Actions</h2>

                    {actionsLoading ? (
                        <div className={styles.loadingState}>Loading actions...</div>
                    ) : filteredActions.length > 0 ? (
                        <div className={styles.actionsList}>
                            {filteredActions.map((action, idx) => (
                                <PerformanceActionCard
                                    key={action.id}
                                    action={action}
                                    index={idx + 1}
                                    onDismiss={handleDismiss}
                                    connectionId={connectionId}
                                />
                            ))}
                        </div>
                    ) : (
                        <div className={styles.emptyState}>
                            {categoryFilter !== 'all'
                                ? `No ${categoryFilter.toLowerCase().replace('_', ' ')} actions found.`
                                : 'No performance actions found. Click "Refresh Actions" to analyze your database.'}
                        </div>
                    )}
                </div>

                {/* Right Column: Table Heatmap */}
                <div className={styles.rightColumn}>
                    <TableHeatmap
                        data={tableUsage}
                        caption="Based on scan frequency and slow query presence"
                    />
                </div>
            </div>
        </div>
    )
}
