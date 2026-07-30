'use client'

import { useState, useEffect, useMemo, useCallback } from 'react'
import { AlertCircle, Play, Loader, TrendingUp, AlertTriangle, CheckCircle, XCircle } from 'lucide-react'
import { useScalabilitySimulation } from './hooks/useScalabilitySimulation'
import { ActionGuard } from '@/components/ActionGuard'
import { HelpTooltip } from './components/HelpTooltip'
import { GROWTH_CATEGORIES, SHARDING_READINESS, SEVERITY_LEVELS } from './utils/helpText'
import { growthMonitoringAPI } from '@/lib/api/client'
import styles from '../Core/RagTrainingTab.module.css'

const PRIORITY_RANK = { CRITICAL: 3, HIGH: 2, MEDIUM: 1, LOW: 0 }

/**
 * Scalability Dashboard Panel component
 * Shows growth simulations and scalability predictions
 */
export function ScalabilityDashboardPanel({ connectionId }) {

    const {
        simulations,
        latest,
        predictions,
        loading,
        simulating,
        error,
        fetchLatest,
        fetchPredictions,
        runSimulation,
        fetchHighRisk
    } = useScalabilitySimulation(connectionId)

    const [selectedScenario, setSelectedScenario] = useState('2X')
    const [expandedTables, setExpandedTables] = useState(new Set())
    const [highRiskTables, setHighRiskTables] = useState([])
    const [fallbackSimulation, setFallbackSimulation] = useState(null)
    const [fallbackPredictions, setFallbackPredictions] = useState([])
    const [fallbackHighRisk, setFallbackHighRisk] = useState([])
    const [fallbackLoading, setFallbackLoading] = useState(false)
    const [fallbackError, setFallbackError] = useState(null)
    const [autoRunAttempted, setAutoRunAttempted] = useState(false)

    useEffect(() => {
        if (connectionId) {
            fetchLatest()
            fetchHighRisk().then(setHighRiskTables)
        }
    }, [connectionId, fetchLatest, fetchHighRisk])

    useEffect(() => {
        if (latest?.id) {
            fetchPredictions(latest.id)
        }
    }, [latest, fetchPredictions])

    useEffect(() => {
        if (!connectionId) return
        if (latest || loading || simulating || autoRunAttempted) return

        const autoRun = async () => {
            try {
                setAutoRunAttempted(true)
                const results = await runSimulation(selectedScenario)
                if (results && results.length > 0 && results[0].id) {
                    await fetchPredictions(results[0].id)
                }
            } catch (err) {
                // Auto-run is best effort; fallback to growth monitoring data
            }
        }

        autoRun()
    }, [connectionId, latest, loading, simulating, autoRunAttempted, selectedScenario, runSimulation, fetchPredictions])

    const scenarioMultiplier = useMemo(() => {
        switch (selectedScenario) {
            case '5X':
                return 5
            case '10X':
                return 10
            case '20X':
                return 20
            case '2X':
            default:
                return 2
        }
    }, [selectedScenario])

    const buildFallbackFromHistory = useCallback((history = [], anomalies = []) => {
        if (!history || history.length === 0) {
            return { simulation: null, predictions: [], highRisk: [] }
        }

        const grouped = new Map()
        history.forEach((entry) => {
            if (!entry?.tableName) return
            const list = grouped.get(entry.tableName) || []
            list.push(entry)
            grouped.set(entry.tableName, list)
        })

        const anomalyMap = new Map()
        anomalies.forEach((anomaly) => {
            if (!anomaly?.tableName) return
            const severity = anomaly.severity || 'INFO'
            const current = anomalyMap.get(anomaly.tableName)
            if (!current || severity === 'CRITICAL' || (severity === 'WARNING' && current !== 'CRITICAL')) {
                anomalyMap.set(anomaly.tableName, severity)
            }
        })

        const predictions = []
        let totalRows = 0
        let totalSizeMb = 0

        grouped.forEach((entries, tableName) => {
            const sorted = entries
                .slice()
                .sort((a, b) => new Date(a.snapshotTimestamp) - new Date(b.snapshotTimestamp))
            const oldest = sorted[0]
            const latestEntry = sorted[sorted.length - 1]

            const currentSizeBytes = latestEntry.sizeBytes || latestEntry.dataSizeBytes || 0
            const currentRows = latestEntry.rowCount || 0
            const oldestSize = oldest.sizeBytes || oldest.dataSizeBytes || 0
            const oldestRows = oldest.rowCount || 0

            let growthRate = 0
            if (oldestSize > 0 && currentSizeBytes > 0) {
                growthRate = ((currentSizeBytes - oldestSize) / oldestSize) * 100
            }
            let rowGrowthRate = 0
            if (oldestRows > 0 && currentRows > 0) {
                rowGrowthRate = ((currentRows - oldestRows) / oldestRows) * 100
            }

            const anomalySeverity = anomalyMap.get(tableName)
            let priority = 'LOW'
            if (anomalySeverity === 'CRITICAL') {
                priority = 'CRITICAL'
            } else if (anomalySeverity === 'WARNING') {
                priority = 'HIGH'
            } else if (growthRate > 50 || rowGrowthRate > 50) {
                priority = 'CRITICAL'
            } else if (growthRate > 20 || rowGrowthRate > 20) {
                priority = 'HIGH'
            } else if (growthRate > 10 || rowGrowthRate > 10) {
                priority = 'MEDIUM'
            }

            const predictedSizeMb = Math.round((currentSizeBytes * scenarioMultiplier) / (1024 * 1024))
            const predictedRows = currentRows ? Math.round(currentRows * scenarioMultiplier) : 0
            const currentSizeMb = Math.round(currentSizeBytes / (1024 * 1024))

            totalRows += currentRows || 0
            totalSizeMb += currentSizeMb || 0

            predictions.push({
                tableName,
                currentRows,
                currentSizeMb,
                predictedRows,
                predictedSizeMb,
                priority,
                lacksPartitioning: false,
                missingCriticalIndexes: false,
            })
        })

        const highRisk = predictions.filter(p => p.priority === 'CRITICAL' || p.priority === 'HIGH')
            .sort((a, b) => (PRIORITY_RANK[b.priority] || 0) - (PRIORITY_RANK[a.priority] || 0))

        const riskLevel = highRisk.some(p => p.priority === 'CRITICAL')
            ? 'CRITICAL'
            : highRisk.some(p => p.priority === 'HIGH')
                ? 'HIGH'
                : predictions.some(p => p.priority === 'MEDIUM')
                    ? 'MEDIUM'
                    : 'LOW'

        const risks = []
        if (highRisk.length > 0) {
            risks.push({ description: `${highRisk.length} tables show elevated growth risk based on recent monitoring data.` })
        }
        if (anomalies.length > 0) {
            const criticalAnomalies = anomalies.filter(a => a.severity === 'CRITICAL').length
            if (criticalAnomalies > 0) {
                risks.push({ description: `${criticalAnomalies} critical growth anomalies detected.` })
            }
        }

        const recommendations = []
        if (highRisk.length > 0) {
            recommendations.push({ title: 'Prioritize retention policies or partitioning for the fastest growing tables.' })
        }
        if (anomalies.length > 0) {
            recommendations.push({ title: 'Investigate recent growth anomalies and validate expected data spikes.' })
        }

        const simulation = {
            growthScenario: selectedScenario,
            overallRiskLevel: riskLevel,
            scalabilityScore: riskLevel === 'CRITICAL' ? 35 : riskLevel === 'HIGH' ? 55 : riskLevel === 'MEDIUM' ? 75 : 90,
            currentTotalRows: totalRows,
            predictedTotalRows: Math.round(totalRows * scenarioMultiplier),
            currentTotalSizeMb: totalSizeMb,
            predictedTotalSizeMb: Math.round(totalSizeMb * scenarioMultiplier),
            risksIdentified: { risks },
            recommendations: { actions: recommendations },
        }

        return { simulation, predictions, highRisk }
    }, [scenarioMultiplier, selectedScenario])

    useEffect(() => {
        if (!connectionId) return
        if (latest || loading) {
            setFallbackSimulation(null)
            setFallbackPredictions([])
            setFallbackHighRisk([])
            setFallbackError(null)
            return
        }

        const loadFallback = async () => {
            setFallbackLoading(true)
            setFallbackError(null)
            try {
                const [historyResp, anomaliesResp] = await Promise.all([
                    growthMonitoringAPI.getGrowthHistory(connectionId, null, 7),
                    growthMonitoringAPI.getAnomalies(connectionId, null, false, 30),
                ])
                const history = historyResp?.history || []
                const anomalies = anomaliesResp?.anomalies || []
                const { simulation, predictions, highRisk } = buildFallbackFromHistory(history, anomalies)
                setFallbackSimulation(simulation)
                setFallbackPredictions(predictions)
                setFallbackHighRisk(highRisk)
            } catch (err) {
                setFallbackError(err?.message || 'Failed to load growth monitoring data')
            } finally {
                setFallbackLoading(false)
            }
        }

        loadFallback()
    }, [connectionId, latest, loading, scenarioMultiplier, buildFallbackFromHistory])

    const handleSimulate = async () => {
        try {
            const result = await runSimulation(selectedScenario)
            if (result && result.length > 0 && result[0].id) {
                await fetchPredictions(result[0].id)
            }
        } catch (err) {
            console.error('Simulation failed:', err)
        }
    }

    const toggleTable = (tableId) => {
        setExpandedTables(prev => {
            const newSet = new Set(prev)
            if (newSet.has(tableId)) {
                newSet.delete(tableId)
            } else {
                newSet.add(tableId)
            }
            return newSet
        })
    }

    const getRiskColor = (riskLevel) => {
        const colorMap = {
            CRITICAL: 'var(--color-danger)',
            HIGH: 'var(--color-danger)',
            MEDIUM: 'var(--color-warning)',
            LOW: 'var(--color-success)'
        }
        return colorMap[riskLevel] || 'var(--color-light-6)'
    }

    const getRiskColors = (riskLevel) => {
        switch (riskLevel) {
            case 'CRITICAL':
            case 'HIGH':
                return { text: 'var(--color-danger)', bg: 'var(--color-danger-soft)' }
            case 'MEDIUM':
                return { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' }
            case 'LOW':
                return { text: 'var(--color-success)', bg: 'var(--color-success-soft)' }
            default:
                return { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
        }
    }

    const getRiskIcon = (riskLevel) => {
        switch (riskLevel) {
            case 'CRITICAL':
            case 'HIGH':
                return <XCircle size={16} />
            case 'MEDIUM':
                return <AlertTriangle size={16} />
            case 'LOW':
                return <CheckCircle size={16} />
            default:
                return <AlertCircle size={16} />
        }
    }

    const getPriorityColor = (priority) => {
        const colorMap = {
            CRITICAL: 'var(--color-danger)',
            HIGH: 'var(--color-danger)',
            MEDIUM: 'var(--color-warning)',
            LOW: 'var(--color-success)'
        }
        return colorMap[priority] || 'var(--color-light-6)'
    }

    const getPriorityColors = (priority) => {
        switch (priority) {
            case 'CRITICAL':
            case 'HIGH':
                return { text: 'var(--color-danger)', bg: 'var(--color-danger-soft)' }
            case 'MEDIUM':
                return { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' }
            case 'LOW':
                return { text: 'var(--color-success)', bg: 'var(--color-success-soft)' }
            default:
                return { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
        }
    }

    const formatBytes = (bytes) => {
        if (!bytes) return '0 MB'
        const mb = bytes / (1024 * 1024)
        if (mb < 1024) return `${mb.toFixed(1)} MB`
        const gb = mb / 1024
        if (gb < 1024) return `${gb.toFixed(1)} GB`
        return `${(gb / 1024).toFixed(1)} TB`
    }

    const effectiveLatest = latest || fallbackSimulation
    const effectivePredictions = latest ? predictions : fallbackPredictions
    const effectiveHighRiskTables = latest ? highRiskTables : fallbackHighRisk
    const sortedPredictions = useMemo(() => {
        return [...effectivePredictions].sort((a, b) => {
            const aRank = PRIORITY_RANK[a.priority] ?? 0
            const bRank = PRIORITY_RANK[b.priority] ?? 0
            if (bRank !== aRank) return bRank - aRank
            const aSize = a.predictedSizeMb || 0
            const bSize = b.predictedSizeMb || 0
            return bSize - aSize
        })
    }, [effectivePredictions])
    const sortedHighRisk = useMemo(() => {
        return [...effectiveHighRiskTables].sort((a, b) => {
            const aRank = PRIORITY_RANK[a.priority] ?? 0
            const bRank = PRIORITY_RANK[b.priority] ?? 0
            if (bRank !== aRank) return bRank - aRank
            const aSize = a.predictedSizeMb || 0
            const bSize = b.predictedSizeMb || 0
            return bSize - aSize
        })
    }, [effectiveHighRiskTables])
    const showFallbackNote = !latest && !!fallbackSimulation
    const hasError = error || fallbackError

    return (
        <div className={styles.brainPanel}>
            <div className={styles.brainHeader}>
                <div>
                    <HelpTooltip
                        content={{
                            title: 'Scalability Simulation',
                            description: 'Projects table growth at 2x, 5x, and 10x current size to identify potential bottlenecks before they occur.',
                            recommendation: 'Run simulations quarterly or before major data migrations to plan capacity.'
                        }}
                    >
                        <h3>Scalability Simulation</h3>
                    </HelpTooltip>
                    <p>Growth predictions and scalability risk assessment</p>
                </div>
                <div className={styles.brainHeaderActions} style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                    <select
                        value={selectedScenario}
                        onChange={(e) => setSelectedScenario(e.target.value)}
                        style={{
                            padding: '8px 12px',
                            border: '1px solid var(--color-light-3)',
                            borderRadius: 'var(--radius-sm)',
                            fontSize: '13px',
                            background: 'var(--color-light-1)'
                        }}
                    >
                        <option value="2X">2X Growth</option>
                        <option value="5X">5X Growth</option>
                        <option value="10X">10X Growth</option>
                        <option value="20X">20X Growth</option>
                    </select>
                    <ActionGuard action="run-scalability-simulation">
                        <button
                            className={styles.profileButton}
                            onClick={handleSimulate}
                            disabled={simulating || !connectionId}
                        >
                            {simulating ? <Loader className={styles.spinner} size={14} /> : <Play size={14} />}
                            {simulating ? 'Simulating...' : 'Run Simulation'}
                        </button>
                    </ActionGuard>
                </div>
            </div>

            {hasError && (
                <div className={styles.brainError}>
                    <AlertCircle size={14} />
                    <span>{error || fallbackError}</span>
                </div>
            )}

            {(loading || fallbackLoading || simulating) && !effectiveLatest && (
                <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
                    <Loader className={styles.spinner} size={24} />
                    <p style={{ marginTop: '12px' }}>
                        {simulating ? 'Running simulation...' : 'Loading simulation...'}
                    </p>
                </div>
            )}

            {effectiveLatest && (
                <>
                    {/* Simulation Overview */}
                    <div
                        style={{
                            background: 'var(--color-light-2)',
                            border: '1px solid var(--color-light-3)',
                            borderRadius: 'var(--radius-md)',
                            padding: '20px',
                            marginBottom: '20px'
                        }}
                    >
                        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px' }}>
                            <div
                                style={{
                                    width: '40px',
                                    height: '40px',
                                    borderRadius: 'var(--radius-md)',
                                    background: getRiskColor(effectiveLatest.overallRiskLevel),
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    color: 'white'
                                }}
                            >
                                {getRiskIcon(effectiveLatest.overallRiskLevel)}
                            </div>
                            <div style={{ flex: 1 }}>
                                <h4 style={{ margin: 0, fontSize: '18px', fontWeight: 600 }}>
                                    {effectiveLatest.growthScenario} Scenario
                                </h4>
                                <p style={{ margin: '4px 0 0', fontSize: '14px', color: 'var(--color-light-6)' }}>
                                    Risk: {effectiveLatest.overallRiskLevel} · Score: {effectiveLatest.scalabilityScore}/100
                                </p>
                                {showFallbackNote && (
                                    <p style={{ margin: '4px 0 0', fontSize: '12px', color: 'var(--color-light-6)' }}>
                                        Using Growth Monitoring data (no prior simulation found).
                                    </p>
                                )}
                            </div>
                        </div>

                        {/* Growth Metrics Grid */}
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: '16px' }}>
                            <div>
                                <div style={{ fontSize: '11px', textTransform: 'uppercase', color: 'var(--color-light-6)', marginBottom: '6px' }}>
                                    Current Rows
                                </div>
                                <div style={{ fontSize: '18px', fontWeight: 600 }}>
                                    {effectiveLatest.currentTotalRows ? effectiveLatest.currentTotalRows.toLocaleString() : 'N/A'}
                                </div>
                            </div>
                            <div>
                                <div style={{ fontSize: '11px', textTransform: 'uppercase', color: 'var(--color-light-6)', marginBottom: '6px' }}>
                                    Predicted Rows
                                </div>
                                <div style={{ fontSize: '18px', fontWeight: 600, color: 'var(--color-dark-grey)' }}>
                                    {effectiveLatest.predictedTotalRows ? effectiveLatest.predictedTotalRows.toLocaleString() : 'N/A'}
                                </div>
                            </div>
                            <div>
                                <div style={{ fontSize: '11px', textTransform: 'uppercase', color: 'var(--color-light-6)', marginBottom: '6px' }}>
                                    Current Size
                                </div>
                                <div style={{ fontSize: '18px', fontWeight: 600 }}>
                                    {formatBytes((effectiveLatest.currentTotalSizeMb || 0) * 1024 * 1024)}
                                </div>
                            </div>
                            <div>
                                <div style={{ fontSize: '11px', textTransform: 'uppercase', color: 'var(--color-light-6)', marginBottom: '6px' }}>
                                    Predicted Size
                                </div>
                                <div style={{ fontSize: '18px', fontWeight: 600, color: 'var(--color-dark-grey)' }}>
                                    {formatBytes((effectiveLatest.predictedTotalSizeMb || 0) * 1024 * 1024)}
                                </div>
                            </div>
                        </div>

                        {/* Risks */}
                        {effectiveLatest.risksIdentified && effectiveLatest.risksIdentified.risks && effectiveLatest.risksIdentified.risks.length > 0 && (
                            <div style={{ marginTop: '16px', padding: '12px', background: 'var(--color-light-1)', borderRadius: 'var(--radius-sm)' }}>
                                <h5 style={{ margin: '0 0 8px', fontSize: '13px', fontWeight: 600 }}>Identified Risks</h5>
                                <ul style={{ margin: 0, paddingLeft: '20px', fontSize: '13px', lineHeight: 1.6 }}>
                                    {effectiveLatest.risksIdentified.risks.map((risk, idx) => (
                                        <li key={idx} style={{ color: 'var(--color-light-8)' }}>
                                            {risk.description}
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}

                        {/* Recommendations */}
                        {effectiveLatest.recommendations && effectiveLatest.recommendations.actions && effectiveLatest.recommendations.actions.length > 0 && (
                            <div style={{ marginTop: '12px', padding: '12px', background: 'var(--color-light-2)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--color-light-3)' }}>
                                <h5 style={{ margin: '0 0 8px', fontSize: '13px', fontWeight: 600, color: 'var(--color-dark-grey)' }}>
                                    Recommendations
                                </h5>
                                <ul style={{ margin: 0, paddingLeft: '20px', fontSize: '13px', lineHeight: 1.6, color: 'var(--color-dark-grey)' }}>
                                    {effectiveLatest.recommendations.actions.map((action, idx) => (
                                        <li key={idx}>{action.title}</li>
                                    ))}
                                </ul>
                            </div>
                        )}
                    </div>

                    {/* High-Risk Tables */}
                    {sortedHighRisk.length > 0 && (
                        <div style={{ marginBottom: '20px' }}>
                            <h4 style={{ margin: '0 0 12px', fontSize: '14px', fontWeight: 600 }}>
                                High-Risk Tables ({sortedHighRisk.length})
                            </h4>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                                {sortedHighRisk.slice(0, 5).map((table, idx) => (
                                    <div
                                        key={idx}
                                        style={{
                                            padding: '12px',
                                            background: 'var(--color-light-2)',
                                            border: `1px solid ${getPriorityColor(table.priority)}40`,
                                            borderRadius: 'var(--radius-sm)',
                                            display: 'flex',
                                            alignItems: 'center',
                                            gap: '12px'
                                        }}
                                    >
                                        <span
                                            style={{
                                                padding: '4px 8px',
                                                borderRadius: 'var(--radius-sm)',
                                                fontSize: '11px',
                                                fontWeight: 600,
                                                background: getPriorityColor(table.priority),
                                                color: 'white'
                                            }}
                                        >
                                            {table.priority}
                                        </span>
                                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '13px', fontWeight: 500 }}>
                                            {table.tableName}
                                        </span>
                                        <span style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>
                                            {table.predictedRows ? table.predictedRows.toLocaleString() : '0'} rows →{' '}
                                            {formatBytes((table.predictedSizeMb || 0) * 1024 * 1024)}
                                        </span>
                                        {table.lacksPartitioning && (
                                            <span
                                                style={{
                                                    marginLeft: 'auto',
                                                    fontSize: '11px',
                                                    padding: '4px 8px',
                                                    background: '#fef2f2',
                                                    color: '#991b1b',
                                                    borderRadius: 'var(--radius-sm)'
                                                }}
                                            >
                                                Needs Partitioning
                                            </span>
                                        )}
                                        {table.missingCriticalIndexes && (
                                            <span
                                                style={{
                                                    marginLeft: table.lacksPartitioning ? '8px' : 'auto',
                                                    fontSize: '11px',
                                                    padding: '4px 8px',
                                                    background: 'var(--color-light-2)',
                                                    color: 'var(--color-dark-grey)',
                                                    borderRadius: 'var(--radius-sm)'
                                                }}
                                            >
                                                Missing Indexes
                                            </span>
                                        )}
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Table Predictions */}
                    {sortedPredictions.length > 0 && (
                        <div>
                            <h4 style={{ margin: '0 0 12px', fontSize: '14px', fontWeight: 600 }}>
                                Table Predictions ({sortedPredictions.length})
                            </h4>
                            <div style={{ border: '1px solid var(--color-light-3)', borderRadius: 'var(--radius-md)', overflow: 'hidden' }}>
                                <table style={{ width: '100%', fontSize: '13px' }}>
                                    <thead style={{ background: 'var(--color-light-2)', borderBottom: '1px solid var(--color-light-3)' }}>
                                        <tr>
                                            <th style={{ padding: '10px', textAlign: 'left', fontWeight: 600 }}>Table</th>
                                            <th style={{ padding: '10px', textAlign: 'right', fontWeight: 600 }}>Current Rows</th>
                                            <th style={{ padding: '10px', textAlign: 'right', fontWeight: 600 }}>Predicted Rows</th>
                                            <th style={{ padding: '10px', textAlign: 'right', fontWeight: 600 }}>Predicted Size</th>
                                            <th style={{ padding: '10px', textAlign: 'center', fontWeight: 600 }}>Priority</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {sortedPredictions.map((pred, idx) => (
                                            <tr
                                                key={idx}
                                                style={{
                                                    borderBottom: idx < sortedPredictions.length - 1 ? '1px solid var(--color-light-3)' : 'none'
                                                }}
                                            >
                                                <td style={{ padding: '10px', fontFamily: 'var(--font-mono)' }}>{pred.tableName}</td>
                                                <td style={{ padding: '10px', textAlign: 'right' }}>
                                                    {pred.currentRows ? pred.currentRows.toLocaleString() : '0'}
                                                </td>
                                                <td style={{ padding: '10px', textAlign: 'right', color: 'var(--color-dark-grey)', fontWeight: 500 }}>
                                                    {pred.predictedRows ? pred.predictedRows.toLocaleString() : '0'}
                                                </td>
                                                <td style={{ padding: '10px', textAlign: 'right' }}>
                                                    {formatBytes((pred.predictedSizeMb || 0) * 1024 * 1024)}
                                                </td>
                                                <td style={{ padding: '10px', textAlign: 'center' }}>
                                                    <span
                                                        style={{
                                                            padding: '4px 8px',
                                                            borderRadius: 'var(--radius-sm)',
                                                            fontSize: '11px',
                                                            fontWeight: 500,
                                                            background: getPriorityColors(pred.priority).bg,
                                                            color: getPriorityColors(pred.priority).text
                                                        }}
                                                    >
                                                        {pred.priority}
                                                    </span>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    )}
                </>
            )}

            {!effectiveLatest && !loading && !fallbackLoading && (
                <div style={{ textAlign: 'center', padding: '48px', color: 'var(--color-light-6)' }}>
                    <TrendingUp size={48} style={{ marginBottom: '16px', opacity: 0.3 }} />
                    <p style={{ fontSize: '15px', marginBottom: '8px' }}>No simulations available</p>
                    <p style={{ fontSize: '13px' }}>Select a growth scenario and click "Run Simulation"</p>
                </div>
            )}
        </div>
    )
}
