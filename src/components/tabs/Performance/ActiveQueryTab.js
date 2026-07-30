'use client'

import { useState } from 'react'
import {
    Activity,
    RefreshCw,
    Loader2,
    Zap,
    Database,
    Clock,
    User,
    Terminal,
    AlertCircle,
    XCircle,
    Filter,
    TrendingUp,
    PlayCircle,
    PauseCircle,
    Search,
    Lock
} from 'lucide-react'
import {
    useActiveQueries,
    useLatestQueries,
    useActiveQueryStatistics,
    useActiveQueryFilterOptions,
    useCaptureQueries,
    useKillQuery,
} from '@/lib/hooks/queries'
import styles from './ActiveQueryTab.module.css'

export default function ActiveQueryTab({ connectionId }) {
    // UI state (not server state)
    const [autoRefresh, setAutoRefresh] = useState(false)
    const [filterType, setFilterType] = useState('ALL')
    const [filterValue, setFilterValue] = useState('')
    const [searchText, setSearchText] = useState('')
    const [sortBy, setSortBy] = useState('durationSeconds')
    const [sortDesc, setSortDesc] = useState(true)

    // Server state with TanStack Query
    const {
        data: queries = [],
        isLoading: loading,
        error: queriesError,
        refetch: refetchQueries,
    } = useActiveQueries(connectionId, { autoRefresh, interval: 3000 })

    const { data: statistics } = useActiveQueryStatistics(connectionId)
    const { data: filterOptions } = useActiveQueryFilterOptions(connectionId)

    // Mutations
    const captureQueriesMutation = useCaptureQueries()
    const killQueryMutation = useKillQuery()

    const capturing = captureQueriesMutation.isPending
    const error = queriesError?.message || captureQueriesMutation.error?.message || killQueryMutation.error?.message

    const captureQueries = () => {
        captureQueriesMutation.mutate(connectionId)
    }

    const killQuery = async (pid) => {
        if (!confirm(`Are you sure you want to kill query with PID ${pid}?`)) {
            return
        }

        killQueryMutation.mutate({ connectionId, pid })
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

    const getStateColor = (state) => {
        if (!state) return 'var(--color-light-6)'
        state = state.toLowerCase()
        if (state === 'active') return 'var(--color-primary)'
        if (state.includes('idle')) return 'var(--color-light-6)'
        if (state.includes('waiting')) return 'var(--color-warning)'
        return 'var(--color-primary)'
    }

    const getStateColors = (state) => {
        if (!state) return { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
        const normalized = state.toLowerCase()
        if (normalized === 'active') return { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' }
        if (normalized.includes('idle')) return { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
        if (normalized.includes('waiting')) return { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' }
        return { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' }
    }

    const formatDuration = (seconds) => {
        if (!seconds || seconds < 0) return '0s'
        if (seconds < 60) return `${seconds}s`
        const minutes = Math.floor(seconds / 60)
        const remainingSeconds = seconds % 60
        if (minutes < 60) return `${minutes}m ${remainingSeconds}s`
        const hours = Math.floor(minutes / 60)
        const remainingMinutes = minutes % 60
        return `${hours}h ${remainingMinutes}m`
    }

    const getFilteredAndSortedQueries = () => {
        let filtered = queries

        // Apply text search
        if (searchText) {
            const search = searchText.toLowerCase()
            filtered = filtered.filter(q =>
                (q.queryText && q.queryText.toLowerCase().includes(search)) ||
                (q.user && q.user.toLowerCase().includes(search)) ||
                (q.database && q.database.toLowerCase().includes(search)) ||
                (q.applicationName && q.applicationName.toLowerCase().includes(search))
            )
        }

        // Apply filter
        if (filterType !== 'ALL' && filterValue) {
            filtered = filtered.filter(q => {
                switch (filterType) {
                    case 'STATE': return q.state === filterValue
                    case 'QUERY_TYPE': return q.queryType === filterValue
                    case 'USER': return q.user === filterValue
                    case 'DATABASE': return q.database === filterValue
                    case 'PRIORITY': return q.priority === filterValue
                    case 'BLOCKED': return q.isBlocked === true
                    default: return true
                }
            })
        }

        // Sort
        filtered.sort((a, b) => {
            let aVal = a[sortBy]
            let bVal = b[sortBy]

            if (aVal === null || aVal === undefined) return 1
            if (bVal === null || bVal === undefined) return -1

            if (typeof aVal === 'string') {
                aVal = aVal.toLowerCase()
                bVal = bVal.toLowerCase()
            }

            if (sortDesc) {
                return aVal < bVal ? 1 : -1
            } else {
                return aVal > bVal ? 1 : -1
            }
        })

        return filtered
    }

    const handleSort = (field) => {
        if (sortBy === field) {
            setSortDesc(!sortDesc)
        } else {
            setSortBy(field)
            setSortDesc(true)
        }
    }

    const filteredQueries = getFilteredAndSortedQueries()

    if (!connectionId) {
        return (
            <div className={styles.emptyState}>
                <Database size={48} className={styles.emptyIcon} />
                <h3>No Connection Selected</h3>
                <p>Please select a database connection to monitor active queries.</p>
            </div>
        )
    }

    return (
        <div className={styles.container}>
            <div className={styles.header}>
                <div className={styles.titleSection}>
                    <h1>Active Query Monitor</h1>
                    <p className={styles.subtitle}>
                        Real-time monitoring of all running database queries
                    </p>
                </div>

                <div className={styles.actions}>
                    <label className={styles.autoRefreshToggle}>
                        <input
                            type="checkbox"
                            checked={autoRefresh}
                            onChange={(e) => setAutoRefresh(e.target.checked)}
                        />
                        {autoRefresh ? <PlayCircle size={16} /> : <PauseCircle size={16} />}
                        <span>Auto-refresh (3s)</span>
                    </label>

                    <button
                        className={styles.refreshButton}
                        onClick={() => refetchQueries()}
                        disabled={loading}
                    >
                        <RefreshCw size={16} className={loading ? styles.spinning : ''} />
                        Refresh
                    </button>

                    <button
                        className={styles.captureButton}
                        onClick={captureQueries}
                        disabled={capturing}
                    >
                        {capturing ? (
                            <>
                                <Loader2 size={16} className={styles.spinning} />
                                Capturing...
                            </>
                        ) : (
                            <>
                                <Zap size={16} />
                                Capture Now
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
                            <Activity size={24} style={{ color: 'var(--color-primary)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Total Queries</div>
                            <div className={styles.statValue}>{statistics.total || 0}</div>
                        </div>
                    </div>

                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-primary-soft)' }}>
                            <PlayCircle size={24} style={{ color: 'var(--color-primary)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Active</div>
                            <div className={styles.statValue}>{statistics.active || 0}</div>
                        </div>
                    </div>

                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-danger-soft)' }}>
                            <Lock size={24} style={{ color: 'var(--color-danger)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Blocked</div>
                            <div className={styles.statValue}>{statistics.blocked || 0}</div>
                        </div>
                    </div>

                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-warning-soft)' }}>
                            <TrendingUp size={24} style={{ color: 'var(--color-warning)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Long-Running</div>
                            <div className={styles.statValue}>{statistics.longRunning || 0}</div>
                        </div>
                    </div>

                    <div className={styles.statCard}>
                        <div className={styles.statIcon} style={{ background: 'var(--color-light-2)' }}>
                            <Clock size={24} style={{ color: 'var(--color-dark-grey)' }} />
                        </div>
                        <div className={styles.statContent}>
                            <div className={styles.statLabel}>Avg Duration</div>
                            <div className={styles.statValue}>
                                {formatDuration(Math.round(statistics.avgDuration || 0))}
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Search and Filter Bar */}
            <div className={styles.filterBar}>
                <div className={styles.searchBox}>
                    <Search size={16} />
                    <input
                        type="text"
                        placeholder="Search queries, users, databases..."
                        value={searchText}
                        onChange={(e) => setSearchText(e.target.value)}
                    />
                </div>

                <div className={styles.filterControls}>
                    <Filter size={16} />
                    <select value={filterType} onChange={(e) => { setFilterType(e.target.value); setFilterValue(''); }}>
                        <option value="ALL">All Queries</option>
                        <option value="STATE">By State</option>
                        <option value="QUERY_TYPE">By Query Type</option>
                        <option value="USER">By User</option>
                        <option value="DATABASE">By Database</option>
                        <option value="PRIORITY">By Priority</option>
                        <option value="BLOCKED">Blocked Only</option>
                    </select>

                    {filterType !== 'ALL' && filterType !== 'BLOCKED' && filterOptions && (
                        <select value={filterValue} onChange={(e) => setFilterValue(e.target.value)}>
                            <option value="">Select {filterType.toLowerCase()}...</option>
                            {filterType === 'STATE' && filterOptions.states?.map(s => (
                                <option key={s} value={s}>{s}</option>
                            ))}
                            {filterType === 'QUERY_TYPE' && filterOptions.queryTypes?.map(t => (
                                <option key={t} value={t}>{t}</option>
                            ))}
                            {filterType === 'USER' && filterOptions.users?.map(u => (
                                <option key={u} value={u}>{u}</option>
                            ))}
                            {filterType === 'DATABASE' && filterOptions.databases?.map(d => (
                                <option key={d} value={d}>{d}</option>
                            ))}
                            {filterType === 'PRIORITY' && filterOptions.priorities?.map(p => (
                                <option key={p} value={p}>{p}</option>
                            ))}
                        </select>
                    )}
                </div>
            </div>

            {/* Queries Table */}
            {loading ? (
                <div className={styles.loadingState}>
                    <Loader2 size={48} className={styles.spinner} />
                    <p>Loading active queries...</p>
                </div>
            ) : filteredQueries.length === 0 ? (
                <div className={styles.emptyState}>
                    <Activity size={48} className={styles.emptyIcon} />
                    <h3>No Queries Found</h3>
                    <p>
                        {queries.length === 0
                            ? 'Click "Capture Now" to fetch active queries from the database.'
                            : 'No queries match the current filter criteria.'}
                    </p>
                </div>
            ) : (
                <div className={styles.tableContainer}>
                    <table className={styles.queryTable}>
                        <thead>
                            <tr>
                                <th onClick={() => handleSort('priority')} className={styles.sortable}>
                                    Priority {sortBy === 'priority' && (sortDesc ? '↓' : '↑')}
                                </th>
                                <th onClick={() => handleSort('pid')} className={styles.sortable}>
                                    PID {sortBy === 'pid' && (sortDesc ? '↓' : '↑')}
                                </th>
                                <th onClick={() => handleSort('user')} className={styles.sortable}>
                                    User {sortBy === 'user' && (sortDesc ? '↓' : '↑')}
                                </th>
                                <th onClick={() => handleSort('database')} className={styles.sortable}>
                                    Database {sortBy === 'database' && (sortDesc ? '↓' : '↑')}
                                </th>
                                <th onClick={() => handleSort('state')} className={styles.sortable}>
                                    State {sortBy === 'state' && (sortDesc ? '↓' : '↑')}
                                </th>
                                <th onClick={() => handleSort('queryType')} className={styles.sortable}>
                                    Type {sortBy === 'queryType' && (sortDesc ? '↓' : '↑')}
                                </th>
                                <th onClick={() => handleSort('durationSeconds')} className={styles.sortable}>
                                    Duration {sortBy === 'durationSeconds' && (sortDesc ? '↓' : '↑')}
                                </th>
                                <th>Query</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {filteredQueries.map((query) => (
                                <tr key={query.id} className={query.isBlocked ? styles.blockedRow : ''}>
                                    <td>
                                        <span
                                            className={styles.priorityBadge}
                                            style={{
                                                background: getPriorityColors(query.priority).bg,
                                                color: getPriorityColors(query.priority).text
                                            }}
                                        >
                                            {query.priority}
                                        </span>
                                    </td>
                                    <td className={styles.pidCell}>{query.pid}</td>
                                    <td>
                                        <div className={styles.userCell}>
                                            <User size={12} />
                                            {query.user || 'N/A'}
                                        </div>
                                    </td>
                                    <td>
                                        <div className={styles.databaseCell}>
                                            <Database size={12} />
                                            {query.database || 'N/A'}
                                        </div>
                                    </td>
                                    <td>
                                        <span
                                            className={styles.stateBadge}
                                            style={{
                                                background: getStateColors(query.state).bg,
                                                color: getStateColors(query.state).text
                                            }}
                                        >
                                            {query.state}
                                        </span>
                                    </td>
                                    <td>
                                        <span className={styles.typeBadge}>{query.queryType}</span>
                                    </td>
                                    <td className={styles.durationCell}>
                                        <Clock size={12} />
                                        {formatDuration(query.durationSeconds)}
                                    </td>
                                    <td className={styles.queryCell}>
                                        <div className={styles.queryText} title={query.queryText}>
                                            {query.queryText || 'No query information'}
                                        </div>
                                        {query.applicationName && (
                                            <div className={styles.appName}>
                                                <Terminal size={10} />
                                                {query.applicationName}
                                            </div>
                                        )}
                                    </td>
                                    <td>
                                        {query.state !== 'idle' && (
                                            <button
                                                className={styles.killButton}
                                                onClick={() => killQuery(query.pid)}
                                                title="Kill query"
                                            >
                                                <XCircle size={14} />
                                            </button>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    )
}
