'use client'

import { useState, useMemo } from 'react'
import { Play, Clock, AlertTriangle, CheckCircle, XCircle, RefreshCw, Bell, BellOff } from 'lucide-react'
import {
    usePlaybooks,
    usePlaybookRunHistory,
    usePlaybookAlerts,
    useExecutePlaybook,
    useAcknowledgePlaybookAlert,
} from '@/lib/hooks/queries'
import styles from './PlaybooksTab.module.css'

export default function PlaybooksTab({ connectionId }) {
    // UI state (kept local)
    const [activeView, setActiveView] = useState('playbooks')
    const [selectedPlaybook, setSelectedPlaybook] = useState(null)
    const [selectedRun, setSelectedRun] = useState(null)
    const [expandedSteps, setExpandedSteps] = useState({})

    // Queries
    const {
        data: playbooksData,
        isLoading: playbooksLoading,
        refetch: refetchPlaybooks,
    } = usePlaybooks()

    const {
        data: runsData,
        isLoading: runsLoading,
        refetch: refetchRuns,
    } = usePlaybookRunHistory(connectionId)

    const {
        data: alertsData,
        isLoading: alertsLoading,
        refetch: refetchAlerts,
    } = usePlaybookAlerts(connectionId)

    // Mutations
    const executePlaybookMutation = useExecutePlaybook()
    const acknowledgeAlertMutation = useAcknowledgePlaybookAlert()

    // Derived state
    const playbooks = useMemo(() => playbooksData?.playbooks || [], [playbooksData])
    const runs = useMemo(() => runsData?.runs || [], [runsData])
    const stats = useMemo(() => runsData?.statistics || null, [runsData])
    const alerts = useMemo(() => alertsData?.alerts || [], [alertsData])
    const loading = playbooksLoading || runsLoading || alertsLoading

    // Refresh all data
    const loadData = () => {
        refetchPlaybooks()
        refetchRuns()
        refetchAlerts()
    }

    const executePlaybook = async (playbookId) => {
        try {
            const data = await executePlaybookMutation.mutateAsync({
                playbookId,
                connectionId,
            })

            if (data.success) {
                setSelectedRun(data.run)
                setActiveView('runs')
            } else {
                alert('Playbook execution failed: ' + data.message)
            }
        } catch (error) {
            console.error('Error executing playbook:', error)
            alert('Failed to execute playbook: ' + error.message)
        }
    }

    const acknowledgeAlert = async (alertId) => {
        try {
            await acknowledgeAlertMutation.mutateAsync({ alertId, acknowledgedBy: 'user' })
        } catch (error) {
            console.error('Error acknowledging alert:', error)
        }
    }

    // Track which playbooks are currently executing
    const executing = useMemo(() => {
        const playbookId = executePlaybookMutation.variables?.playbookId
        if (executePlaybookMutation.isPending && playbookId) {
            return { [playbookId]: true }
        }
        return {}
    }, [executePlaybookMutation.isPending, executePlaybookMutation.variables])

    const getCategoryIcon = (category) => {
        switch (category) {
            case 'health': return '🏥'
            case 'performance': return '⚡'
            case 'troubleshooting': return '🔧'
            case 'monitoring': return '📊'
            case 'maintenance': return '🔨'
            default: return '📋'
        }
    }

    const getStatusIcon = (status) => {
        switch (status) {
            case 'COMPLETED':
                return <CheckCircle className={styles.statusIconSuccess} size={20} />
            case 'FAILED':
                return <XCircle className={styles.statusIconError} size={20} />
            case 'RUNNING':
                return <RefreshCw className={styles.statusIconRunning} size={20} />
            default:
                return <Clock className={styles.statusIconPending} size={20} />
        }
    }

    const getSeverityClass = (severity) => {
        switch (severity) {
            case 'CRITICAL': return styles.severityCritical
            case 'WARNING': return styles.severityWarning
            case 'INFO': return styles.severityInfo
            default: return ''
        }
    }

    const renderStepData = (data) => {
        if (!data) return null

        // Handle array data (like list of indexes, queries, etc.)
        if (Array.isArray(data.indexes) || Array.isArray(data.slow_queries) ||
            Array.isArray(data.active_queries) || Array.isArray(data.long_running_queries) ||
            Array.isArray(data.tables) || Array.isArray(data.all_indexes)) {

            const arrayData = data.indexes || data.slow_queries || data.active_queries ||
                             data.long_running_queries || data.tables || data.all_indexes || []

            if (arrayData.length === 0) {
                return <div className={styles.noData}>No data found</div>
            }

            // Get column headers from first item
            const firstItem = arrayData[0]
            const headers = Object.keys(firstItem)

            return (
                <div className={styles.dataTableContainer}>
                    <div className={styles.dataCount}>
                        Showing {arrayData.length} item(s)
                    </div>
                    <table className={styles.dataTable}>
                        <thead>
                            <tr>
                                {headers.map((header, i) => (
                                    <th key={i}>{header.replace(/_/g, ' ').toUpperCase()}</th>
                                ))}
                            </tr>
                        </thead>
                        <tbody>
                            {arrayData.map((row, i) => (
                                <tr key={i}>
                                    {headers.map((header, j) => (
                                        <td key={j}>
                                            {typeof row[header] === 'object'
                                                ? JSON.stringify(row[header])
                                                : String(row[header] ?? '')}
                                        </td>
                                    ))}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )
        }

        // Handle object/summary data
        return (
            <div className={styles.dataSummary}>
                {Object.entries(data).map(([key, value]) => {
                    if (Array.isArray(value) && value.length === 0) return null

                    return (
                        <div key={key} className={styles.summaryItem}>
                            <strong>{key.replace(/_/g, ' ')}:</strong>{' '}
                            {typeof value === 'object'
                                ? JSON.stringify(value, null, 2)
                                : String(value)}
                        </div>
                    )
                })}
            </div>
        )
    }

    if (loading && playbooks.length === 0) {
        return <div className={styles.loading}>Loading playbooks...</div>
    }

    return (
        <div className={styles.container}>
            <div className={styles.header}>
                <h2>Playbooks</h2>
                <div className={styles.viewTabs}>
                    <button
                        className={activeView === 'playbooks' ? styles.activeTab : ''}
                        onClick={() => setActiveView('playbooks')}
                    >
                        📋 Playbooks ({playbooks.length})
                    </button>
                    <button
                        className={activeView === 'runs' ? styles.activeTab : ''}
                        onClick={() => setActiveView('runs')}
                    >
                        <Clock size={16} /> History ({runs.length})
                    </button>
                    <button
                        className={activeView === 'alerts' ? styles.activeTab : ''}
                        onClick={() => setActiveView('alerts')}
                    >
                        <Bell size={16} /> Alerts ({alerts.filter(a => !a.acknowledged).length})
                    </button>
                </div>
                <button className={styles.refreshButton} onClick={loadData}>
                    <RefreshCw size={16} /> Refresh
                </button>
            </div>

            {/* Statistics */}
            {stats && activeView === 'runs' && (
                <div className={styles.statsRow}>
                    <div className={styles.statCard}>
                        <div className={styles.statValue}>{stats.total_runs}</div>
                        <div className={styles.statLabel}>Total Runs</div>
                    </div>
                    <div className={styles.statCard}>
                        <div className={styles.statValue}>{stats.completed_runs}</div>
                        <div className={styles.statLabel}>Successful</div>
                    </div>
                    <div className={styles.statCard}>
                        <div className={styles.statValue}>{stats.failed_runs}</div>
                        <div className={styles.statLabel}>Failed</div>
                    </div>
                    <div className={styles.statCard}>
                        <div className={styles.statValue}>{stats.success_rate.toFixed(1)}%</div>
                        <div className={styles.statLabel}>Success Rate</div>
                    </div>
                </div>
            )}

            {/* Playbooks View */}
            {activeView === 'playbooks' && (
                <div className={styles.playbooksGrid}>
                    {playbooks.map((playbook) => (
                        <div key={playbook.id} className={styles.playbookCard}>
                            <div className={styles.playbookHeader}>
                                <div className={styles.playbookTitle}>
                                    <span className={styles.categoryIcon}>
                                        {getCategoryIcon(playbook.category)}
                                    </span>
                                    <h3>{playbook.name}</h3>
                                </div>
                                {playbook.isActive ? (
                                    <span className={styles.badgeActive}>Active</span>
                                ) : (
                                    <span className={styles.badgeInactive}>Inactive</span>
                                )}
                            </div>

                            <p className={styles.playbookDescription}>{playbook.description}</p>

                            <div className={styles.playbookMeta}>
                                <span className={styles.metaItem}>
                                    <strong>Category:</strong> {playbook.category}
                                </span>
                                <span className={styles.metaItem}>
                                    <strong>Steps:</strong> {playbook.steps?.length || 0}
                                </span>
                                {playbook.scheduleCron && (
                                    <span className={styles.metaItem}>
                                        <Clock size={14} /> Scheduled
                                    </span>
                                )}
                            </div>

                            <div className={styles.playbookActions}>
                                <button
                                    className={styles.executeButton}
                                    onClick={() => executePlaybook(playbook.id)}
                                    disabled={!playbook.isActive || executing[playbook.id]}
                                >
                                    {executing[playbook.id] ? (
                                        <>
                                            <RefreshCw className={styles.spinning} size={16} />
                                            Executing...
                                        </>
                                    ) : (
                                        <>
                                            <Play size={16} />
                                            Execute Now
                                        </>
                                    )}
                                </button>
                                <button
                                    className={styles.detailsButton}
                                    onClick={() => setSelectedPlaybook(playbook)}
                                >
                                    View Details
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {/* Runs History View */}
            {activeView === 'runs' && (
                <div className={styles.runsList}>
                    {runs.length === 0 ? (
                        <div className={styles.empty}>
                            No playbook runs yet. Execute a playbook to see history.
                        </div>
                    ) : (
                        runs.map((run) => {
                            const playbook = playbooks.find(p => p.id === run.playbookId)

                            return (
                                <div
                                    key={run.id}
                                    className={styles.runCard}
                                    onClick={() => setSelectedRun(run)}
                                >
                                    <div className={styles.runHeader}>
                                        <div className={styles.runTitle}>
                                            {getStatusIcon(run.status)}
                                            <h4>{playbook?.name || 'Unknown Playbook'}</h4>
                                        </div>
                                        <span className={styles.runTime}>
                                            {new Date(run.startedAt).toLocaleString()}
                                        </span>
                                    </div>

                                    <div className={styles.runMeta}>
                                        <span>Status: <strong>{run.status}</strong></span>
                                        <span>Steps: {run.stepsCompleted}/{run.stepsTotal}</span>
                                        {run.durationMs && (
                                            <span>Duration: {(run.durationMs / 1000).toFixed(2)}s</span>
                                        )}
                                        {run.alertsTriggered > 0 && (
                                            <span className={styles.alertBadge}>
                                                <AlertTriangle size={14} />
                                                {run.alertsTriggered} Alert(s)
                                            </span>
                                        )}
                                    </div>

                                    {run.errorMessage && (
                                        <div className={styles.errorMessage}>
                                            Error: {run.errorMessage}
                                        </div>
                                    )}
                                </div>
                            )
                        })
                    )}
                </div>
            )}

            {/* Alerts View */}
            {activeView === 'alerts' && (
                <div className={styles.alertsList}>
                    {alerts.length === 0 ? (
                        <div className={styles.empty}>
                            No alerts. Your database is healthy!
                        </div>
                    ) : (
                        alerts.map((alert) => (
                            <div
                                key={alert.id}
                                className={`${styles.alertCard} ${getSeverityClass(alert.severity)} ${
                                    alert.acknowledged ? styles.acknowledged : ''
                                }`}
                            >
                                <div className={styles.alertHeader}>
                                    <div className={styles.alertTitle}>
                                        <AlertTriangle size={20} />
                                        <h4>{alert.title}</h4>
                                    </div>
                                    <div className={styles.alertActions}>
                                        <span className={styles.severityBadge}>
                                            {alert.severity}
                                        </span>
                                        {!alert.acknowledged && (
                                            <button
                                                className={styles.ackButton}
                                                onClick={() => acknowledgeAlert(alert.id)}
                                            >
                                                <BellOff size={16} />
                                                Acknowledge
                                            </button>
                                        )}
                                    </div>
                                </div>

                                <p className={styles.alertMessage}>{alert.message}</p>

                                <div className={styles.alertMeta}>
                                    <span>{new Date(alert.createdAt).toLocaleString()}</span>
                                    {alert.acknowledged && (
                                        <span className={styles.acknowledgedBy}>
                                            Acknowledged by {alert.acknowledgedBy}
                                        </span>
                                    )}
                                </div>

                                {alert.recommendations && alert.recommendations.length > 0 && (
                                    <div className={styles.recommendations}>
                                        <strong>Recommendations:</strong>
                                        <ul>
                                            {alert.recommendations.map((rec, idx) => (
                                                <li key={idx}>{rec.recommendation}</li>
                                            ))}
                                        </ul>
                                    </div>
                                )}
                            </div>
                        ))
                    )}
                </div>
            )}

            {/* Selected Run Details Modal */}
            {selectedRun && (
                <div className={styles.modal} onClick={() => setSelectedRun(null)}>
                    <div className={styles.modalContent} onClick={(e) => e.stopPropagation()}>
                        <div className={styles.modalHeader}>
                            <h3>Playbook Run Details</h3>
                            <button className={styles.closeButton} onClick={() => setSelectedRun(null)}>
                                ✕
                            </button>
                        </div>

                        <div className={styles.modalBody}>
                            <div className={styles.runDetail}>
                                <strong>Status:</strong> {selectedRun.status}
                            </div>
                            <div className={styles.runDetail}>
                                <strong>Started:</strong> {new Date(selectedRun.startedAt).toLocaleString()}
                            </div>
                            {selectedRun.completedAt && (
                                <div className={styles.runDetail}>
                                    <strong>Completed:</strong> {new Date(selectedRun.completedAt).toLocaleString()}
                                </div>
                            )}
                            {selectedRun.durationMs && (
                                <div className={styles.runDetail}>
                                    <strong>Duration:</strong> {(selectedRun.durationMs / 1000).toFixed(2)}s
                                </div>
                            )}

                            <h4>Step Results:</h4>
                            {selectedRun.findings && selectedRun.findings.length > 0 ? (
                                <div className={styles.stepsList}>
                                    {selectedRun.findings.map((finding, idx) => {
                                        const isExpanded = expandedSteps[idx]
                                        const hasData = finding.data && Object.keys(finding.data).length > 0

                                        return (
                                            <div
                                                key={idx}
                                                className={`${styles.stepCard} ${hasData ? styles.clickable : ''}`}
                                                onClick={() => {
                                                    if (hasData) {
                                                        setExpandedSteps(prev => ({
                                                            ...prev,
                                                            [idx]: !prev[idx]
                                                        }))
                                                    }
                                                }}
                                            >
                                                <div className={styles.stepHeader}>
                                                    <div className={styles.stepHeaderLeft}>
                                                        <strong>{finding.stepName}</strong>
                                                        {hasData && (
                                                            <span className={styles.expandIndicator}>
                                                                {isExpanded ? '▼' : '▶'} Click to view details
                                                            </span>
                                                        )}
                                                    </div>
                                                    <span className={styles[`status${finding.status}`]}>
                                                        {finding.status}
                                                    </span>
                                                </div>

                                                {finding.issues && finding.issues.length > 0 && (
                                                    <ul className={styles.issuesList}>
                                                        {finding.issues.map((issue, i) => (
                                                            <li key={i}>{issue}</li>
                                                        ))}
                                                    </ul>
                                                )}

                                                {/* Expandable Data Section */}
                                                {isExpanded && hasData && (
                                                    <div className={styles.stepData}>
                                                        {renderStepData(finding.data)}
                                                    </div>
                                                )}

                                                {finding.executionTimeMs && (
                                                    <div className={styles.stepTime}>
                                                        Execution time: {finding.executionTimeMs}ms
                                                    </div>
                                                )}
                                            </div>
                                        )
                                    })}
                                </div>
                            ) : (
                                <p>No step results available.</p>
                            )}
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}
