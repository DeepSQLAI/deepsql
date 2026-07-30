'use client'

import { useState, useEffect } from 'react'
import { AlertCircle, Play, Loader, AlertTriangle, XCircle, AlertOctagon, Info, ChevronDown, ChevronRight } from 'lucide-react'
import { useQueryAntiPatterns } from './hooks/useQueryAntiPatterns'
import { ActionGuard } from '@/components/ActionGuard'
import { HelpTooltip } from './components/HelpTooltip'
import { SEVERITY_LEVELS, ANTI_PATTERNS } from './utils/helpText'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Query Anti-Patterns Panel component
 * Shows detected query quality issues
 */
export function QueryAntiPatternsPanel({ connectionId }) {

    const {
        patterns,
        loading,
        analyzing,
        error,
        fetchPatterns,
        analyzePatterns,
        refetchPatterns
    } = useQueryAntiPatterns(connectionId)

    const [severityFilter, setSeverityFilter] = useState('')
    const [expandedPatterns, setExpandedPatterns] = useState(new Set())
    const [analysisResult, setAnalysisResult] = useState(null)

    useEffect(() => {
        if (connectionId) {
            fetchPatterns()
        }
    }, [connectionId, fetchPatterns])

    const handleAnalyze = async () => {
        try {
            const result = await analyzePatterns()
            setAnalysisResult(result || null)
            await refetchPatterns()
        } catch (err) {
            console.error('Analysis failed:', err)
            setAnalysisResult(null)
        }
    }

    const togglePattern = (patternId) => {
        setExpandedPatterns(prev => {
            const newSet = new Set(prev)
            if (newSet.has(patternId)) {
                newSet.delete(patternId)
            } else {
                newSet.add(patternId)
            }
            return newSet
        })
    }

    const getSeverityIcon = (severity) => {
        switch (severity) {
            case 'CRITICAL':
                return <AlertOctagon size={16} />
            case 'HIGH':
                return <AlertTriangle size={16} />
            case 'MEDIUM':
                return <AlertCircle size={16} />
            case 'LOW':
                return <Info size={16} />
            default:
                return <AlertCircle size={16} />
        }
    }

    const getSeverityColor = (severity) => {
        const colorMap = {
            CRITICAL: 'var(--color-danger)',
            HIGH: 'var(--color-danger)',
            MEDIUM: 'var(--color-warning)',
            LOW: 'var(--color-primary)'
        }
        return colorMap[severity] || 'var(--color-light-6)'
    }

    const getSeverityColors = (severity) => {
        switch (severity) {
            case 'CRITICAL':
            case 'HIGH':
                return {
                    text: 'var(--color-danger)',
                    bg: 'var(--color-danger-soft)',
                    border: 'var(--color-danger-border)'
                }
            case 'MEDIUM':
                return {
                    text: 'var(--color-warning)',
                    bg: 'var(--color-warning-soft)',
                    border: 'var(--color-warning)'
                }
            case 'LOW':
                return {
                    text: 'var(--color-primary)',
                    bg: 'var(--color-primary-soft)',
                    border: 'var(--color-primary-light)'
                }
            default:
                return {
                    text: 'var(--color-light-6)',
                    bg: 'var(--color-light-2)',
                    border: 'var(--color-light-3)'
                }
        }
    }

    const filteredPatterns = severityFilter
        ? patterns.filter(p => p.severity === severityFilter)
        : patterns

    const severityCounts = patterns.reduce((acc, p) => {
        acc[p.severity] = (acc[p.severity] || 0) + 1
        return acc
    }, {})

    return (
        <div className={styles.brainPanel}>
            <div className={styles.brainHeader}>
                <div>
                    <HelpTooltip
                        content={{
                            title: 'Query Anti-Patterns',
                            description: 'Anti-patterns are common SQL query issues that can cause performance problems, excessive resource usage, or maintenance difficulties.',
                            recommendation: 'Address CRITICAL and HIGH severity issues first for maximum impact.'
                        }}
                    >
                        <h3>Query Anti-Patterns</h3>
                    </HelpTooltip>
                    <p>Detected query quality issues and performance anti-patterns</p>
                </div>
                <div className={styles.brainHeaderActions}>
                    <ActionGuard action="analyze-query-patterns">
                        <button
                            className={styles.profileButton}
                            onClick={handleAnalyze}
                            disabled={analyzing || !connectionId}
                        >
                            {analyzing ? <Loader className={styles.spinner} size={14} /> : <Play size={14} />}
                            {analyzing ? 'Analyzing...' : 'Analyze Queries'}
                        </button>
                    </ActionGuard>
                </div>
            </div>

            {error && (
                <div className={styles.brainError}>
                    <AlertCircle size={14} />
                    <span>{error}</span>
                </div>
            )}

            {analysisResult && (
                <div style={{
                    marginBottom: '16px',
                    padding: '12px 16px',
                    background: 'var(--color-light-1)',
                    borderRadius: '8px',
                    border: '1px solid var(--color-light-3)',
                    fontSize: '13px',
                    color: 'var(--color-dark-grey)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px'
                }}>
                    <span>{analysisResult.message || 'Analysis complete.'}</span>
                    {analysisResult.totalPatterns !== undefined && (
                        <span style={{ marginLeft: 'auto', fontWeight: 600 }}>
                            {analysisResult.totalPatterns} patterns
                            {analysisResult.criticalCount !== undefined && ` · ${analysisResult.criticalCount} critical`}
                            {analysisResult.highCount !== undefined && ` · ${analysisResult.highCount} high`}
                        </span>
                    )}
                </div>
            )}

            {/* Summary Stats */}
            {patterns.length > 0 && (
                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
                        gap: '12px',
                        marginBottom: '20px'
                    }}
                >
                    <div
                        style={{
                            background: 'var(--color-light-2)',
                            border: '1px solid var(--color-light-3)',
                            borderRadius: 'var(--radius-md)',
                            padding: '16px'
                        }}
                    >
                        <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '6px' }}>
                            Total Patterns
                        </div>
                        <div style={{ fontSize: '24px', fontWeight: 600 }}>{patterns.length}</div>
                    </div>
                    <div
                        style={{
                            background: 'var(--color-danger-soft)',
                            border: '1px solid var(--color-danger-border)',
                            borderRadius: 'var(--radius-md)',
                            padding: '16px'
                        }}
                    >
                        <HelpTooltip content={SEVERITY_LEVELS.CRITICAL}>
                            <div style={{ fontSize: '12px', color: 'var(--color-danger-text)', marginBottom: '6px' }}>
                                Critical
                            </div>
                        </HelpTooltip>
                        <div style={{ fontSize: '24px', fontWeight: 600, color: 'var(--color-danger)' }}>
                            {severityCounts.CRITICAL || 0}
                        </div>
                    </div>
                    <div
                        style={{
                            background: 'var(--color-warning-soft)',
                            border: '1px solid var(--color-warning)',
                            borderRadius: 'var(--radius-md)',
                            padding: '16px'
                        }}
                    >
                        <HelpTooltip content={SEVERITY_LEVELS.HIGH}>
                            <div style={{ fontSize: '12px', color: 'var(--color-warning)', marginBottom: '6px' }}>
                                High
                            </div>
                        </HelpTooltip>
                        <div style={{ fontSize: '24px', fontWeight: 600, color: 'var(--color-warning)' }}>
                            {severityCounts.HIGH || 0}
                        </div>
                    </div>
                    <div
                        style={{
                            background: 'var(--color-primary-soft)',
                            border: '1px solid var(--color-primary-light)',
                            borderRadius: 'var(--radius-md)',
                            padding: '16px'
                        }}
                    >
                        <HelpTooltip content={SEVERITY_LEVELS.MEDIUM}>
                            <div style={{ fontSize: '12px', color: 'var(--color-primary)', marginBottom: '6px' }}>
                                Medium
                            </div>
                        </HelpTooltip>
                        <div style={{ fontSize: '24px', fontWeight: 600, color: 'var(--color-primary)' }}>
                            {severityCounts.MEDIUM || 0}
                        </div>
                    </div>
                </div>
            )}

            {/* Severity Filter */}
            <div style={{ marginBottom: '16px' }}>
                <select
                    value={severityFilter}
                    onChange={(e) => setSeverityFilter(e.target.value)}
                    style={{
                        padding: '8px 12px',
                        border: '1px solid var(--color-light-3)',
                        borderRadius: 'var(--radius-sm)',
                        fontSize: '13px',
                        background: 'var(--color-light-1)'
                    }}
                >
                    <option value="">All Severities</option>
                    <option value="CRITICAL">Critical Only</option>
                    <option value="HIGH">High Only</option>
                    <option value="MEDIUM">Medium Only</option>
                    <option value="LOW">Low Only</option>
                </select>
            </div>

            {/* Anti-Patterns List */}
            {loading && patterns.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
                    <Loader className={styles.spinner} size={24} />
                    <p style={{ marginTop: '12px' }}>Loading anti-patterns...</p>
                </div>
            ) : filteredPatterns.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '48px', color: 'var(--color-light-6)' }}>
                    <AlertCircle size={48} style={{ marginBottom: '16px', opacity: 0.3 }} />
                    <p style={{ fontSize: '15px', marginBottom: '8px' }}>
                        {patterns.length === 0 ? 'No anti-patterns detected' : 'No patterns match filter'}
                    </p>
                    {patterns.length === 0 && (
                        <>
                            <p style={{ fontSize: '13px', marginBottom: '6px' }}>
                                Click "Analyze Queries" to detect query quality issues.
                            </p>
                            <p style={{ fontSize: '12px' }}>
                                This analysis uses recent slow query history (default lookback is 90 days).
                                Ingest slow query logs first if you have no history.
                            </p>
                        </>
                    )}
                </div>
            ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                    {filteredPatterns.map((pattern) => {
                        const isExpanded = expandedPatterns.has(pattern.id)
                        const severityColors = getSeverityColors(pattern.severity)

                        return (
                            <div
                                key={pattern.id}
                                style={{
                                    border: `1px solid ${severityColors.border}`,
                                    borderRadius: 'var(--radius-md)',
                                    overflow: 'hidden'
                                }}
                            >
                                {/* Pattern Header */}
                                <div
                                    onClick={() => togglePattern(pattern.id)}
                                    style={{
                                        padding: '16px',
                                        background: severityColors.bg,
                                        cursor: 'pointer',
                                        display: 'flex',
                                        alignItems: 'flex-start',
                                        gap: '12px'
                                    }}
                                >
                                    <div
                                        style={{
                                            width: '32px',
                                            height: '32px',
                                            borderRadius: 'var(--radius-sm)',
                                            background: severityColors.text,
                                            color: 'white',
                                            display: 'flex',
                                            alignItems: 'center',
                                            justifyContent: 'center',
                                            flexShrink: 0
                                        }}
                                    >
                                        {getSeverityIcon(pattern.severity)}
                                    </div>
                                    <div style={{ flex: 1, minWidth: 0 }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                                            <h4 style={{ margin: 0, fontSize: '14px', fontWeight: 600 }}>
                                                {pattern.title}
                                            </h4>
                                            <span
                                                style={{
                                                    padding: '2px 6px',
                                                    borderRadius: 'var(--radius-sm)',
                                                    fontSize: '11px',
                                                    fontWeight: 500,
                                                    background: severityColors.text,
                                                    color: 'white'
                                                }}
                                            >
                                                {pattern.severity}
                                            </span>
                                        </div>
                                        <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--color-light-7)' }}>
                                            {pattern.description}
                                        </p>
                                        <div style={{ marginTop: '8px', fontSize: '12px', color: 'var(--color-light-6)' }}>
                                            <span>Occurrences: {pattern.occurrences}</span>
                                            <span style={{ margin: '0 8px' }}>·</span>
                                            <span>Avg Time: {pattern.avgExecutionTimeMs}ms</span>
                                            {pattern.examinationRatio && (
                                                <>
                                                    <span style={{ margin: '0 8px' }}>·</span>
                                                    <span>Examination Ratio: {pattern.examinationRatio}:1</span>
                                                </>
                                            )}
                                        </div>
                                    </div>
                                    <div style={{ flexShrink: 0, color: 'var(--color-light-6)' }}>
                                        {isExpanded ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
                                    </div>
                                </div>

                                {/* Pattern Details (Expanded) */}
                                {isExpanded && (
                                    <div style={{ padding: '16px', background: 'var(--color-light-1)', borderTop: '1px solid var(--color-light-3)' }}>
                                        <div style={{ marginBottom: '16px' }}>
                                            <h5 style={{ margin: '0 0 8px', fontSize: '13px', fontWeight: 600, color: 'var(--color-light-7)' }}>
                                                Recommendation
                                            </h5>
                                            <p style={{ margin: 0, fontSize: '13px', color: 'var(--color-light-8)', lineHeight: 1.5 }}>
                                                {pattern.recommendation}
                                            </p>
                                        </div>

                                        {pattern.exampleQuery && (
                                            <div>
                                                <h5 style={{ margin: '0 0 8px', fontSize: '13px', fontWeight: 600, color: 'var(--color-light-7)' }}>
                                                    Example Query
                                                </h5>
                                                <pre
                                                    style={{
                                                        margin: 0,
                                                        padding: '12px',
                                                        background: 'var(--color-dark-3)',
                                                        border: '1px solid var(--color-light-3)',
                                                        borderRadius: 'var(--radius-sm)',
                                                        fontSize: '12px',
                                                        fontFamily: 'var(--font-mono)',
                                                        overflow: 'auto',
                                                        color: 'var(--color-light-9)',
                                                        maxHeight: '200px'
                                                    }}
                                                >
                                                    {pattern.exampleQuery}
                                                </pre>
                                            </div>
                                        )}
                                    </div>
                                )}
                            </div>
                        )
                    })}
                </div>
            )}
        </div>
    )
}
