'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import {
  AlertCircle,
  Loader,
  RefreshCw,
  Target,
  Lightbulb,
  CheckCircle,
  XCircle,
  TrendingUp,
  Database,
  Play,
  StopCircle,
  Wrench,
  Copy,
  Check,
  ThumbsUp,
  ThumbsDown
} from 'lucide-react'
import { brainAPI } from '@/lib/api/client'
import { ActionGuard } from '@/components/ActionGuard'
import { HelpTooltip } from './components/HelpTooltip'
import { QUERY_INTELLIGENCE } from './utils/helpText'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Query Intelligence Panel - Brain 2.0 ML Feature
 * Shows cardinality estimation accuracy and reliable query plan patterns.
 * Data is populated automatically when slow queries are ingested.
 * Note: Column statistics are now consolidated with Key Column Analysis in the Overview tab.
 */
export function QueryIntelligencePanel({ connectionId }) {
  const [activeTab, setActiveTab] = useState('accuracy')
  const [accuracy, setAccuracy] = useState(null)
  const [patterns, setPatterns] = useState([])
  const [patternStats, setPatternStats] = useState(null)
  const [loading, setLoading] = useState(false)
  const [backfilling, setBackfilling] = useState(false)
  const [error, setError] = useState(null)
  const [backfillResult, setBackfillResult] = useState(null)
  const [explainProgress, setExplainProgress] = useState(null)
  const [copiedSql, setCopiedSql] = useState(null)
  const progressPollRef = useRef(null)

  const fetchData = useCallback(async () => {
    if (!connectionId) return

    setLoading(true)
    setError(null)
    try {
      const [accuracyData, patternsData, patternStatsData] = await Promise.allSettled([
        brainAPI.getCardinalityAccuracy(connectionId),
        brainAPI.getAllPatterns(connectionId, 20),
        brainAPI.getPatternStats(connectionId)
      ])

      if (accuracyData.status === 'fulfilled') setAccuracy(accuracyData.value)
      if (patternsData.status === 'fulfilled') setPatterns(patternsData.value || [])
      if (patternStatsData.status === 'fulfilled') setPatternStats(patternStatsData.value)
    } catch (err) {
      setError(err.message || 'Failed to load query intelligence data')
    } finally {
      setLoading(false)
    }
  }, [connectionId])

  const startProgressPolling = useCallback(() => {
    if (progressPollRef.current) return
    progressPollRef.current = setInterval(async () => {
      try {
        const result = await brainAPI.getExplainProgress(connectionId)
        // Convert backend format to frontend format with detailed failure info
        const progress = {
          running: result.status === 'RUNNING',
          processed: result.processedQueries || 0,
          total: result.totalQueries || 0,
          errors: result.failedExplains || 0,
          successful: result.successfulExplains || 0,
          // Detailed failure breakdown
          nonExplainableQueries: result.nonExplainableQueries || 0,
          noRowEstimateQueries: result.noRowEstimateQueries || 0,
          connectionErrors: result.connectionErrors || 0,
          parseErrors: result.parseErrors || 0,
          sampleFailures: result.sampleFailures || [],
          failureSummary: result.failureSummary
        }
        setExplainProgress(progress)
        if (!progress.running) {
          clearInterval(progressPollRef.current)
          progressPollRef.current = null
          fetchData() // Refresh accuracy data when done
        }
      } catch {
        clearInterval(progressPollRef.current)
        progressPollRef.current = null
      }
    }, 2000) // Poll every 2 seconds
  }, [connectionId, fetchData])

  const checkExplainProgress = useCallback(async () => {
    if (!connectionId) return
    try {
      const result = await brainAPI.getExplainProgress(connectionId)
      // Convert backend format to frontend format with detailed failure info
      const progress = {
        running: result.status === 'RUNNING',
        processed: result.processedQueries || 0,
        total: result.totalQueries || 0,
        errors: result.failedExplains || 0,
        successful: result.successfulExplains || 0,
        // Detailed failure breakdown
        nonExplainableQueries: result.nonExplainableQueries || 0,
        noRowEstimateQueries: result.noRowEstimateQueries || 0,
        connectionErrors: result.connectionErrors || 0,
        parseErrors: result.parseErrors || 0,
        sampleFailures: result.sampleFailures || [],
        failureSummary: result.failureSummary
      }
      if (progress.running) {
        setExplainProgress(progress)
        startProgressPolling()
      } else if (progress.total > 0 && result.status === 'COMPLETED') {
        // Job just finished
        setExplainProgress(progress)
        fetchData() // Refresh accuracy data
      }
    } catch {
      // Ignore - no progress yet
    }
  }, [connectionId, fetchData, startProgressPolling])

  useEffect(() => {
    fetchData()
    // Check for existing EXPLAIN progress on mount
    checkExplainProgress()
    return () => {
      if (progressPollRef.current) {
        clearInterval(progressPollRef.current)
      }
    }
  }, [fetchData, checkExplainProgress])

  const handleStartExplainBackfill = async () => {
    if (!connectionId) return
    setError(null)
    try {
      const result = await brainAPI.startExplainBackfill(connectionId, 500)
      // Backend returns status: "RUNNING", "COMPLETED", "CANCELLED", "NOT_STARTED"
      if (result.status === 'RUNNING') {
        setExplainProgress({
          running: true,
          processed: result.processedQueries || 0,
          total: result.totalQueries || 0,
          errors: result.failedExplains || 0
        })
        startProgressPolling()
      } else if (result.status === 'COMPLETED' && result.totalQueries === 0) {
        setError('No queries found that need EXPLAIN data')
      } else {
        setError(result.errorMessage || 'Failed to start EXPLAIN calculation')
      }
    } catch (err) {
      setError(err.message || 'Failed to start EXPLAIN calculation')
    }
  }

  const handleCancelExplain = async () => {
    if (!connectionId) return
    try {
      await brainAPI.cancelExplainBackfill(connectionId)
      if (progressPollRef.current) {
        clearInterval(progressPollRef.current)
        progressPollRef.current = null
      }
      setExplainProgress(null)
    } catch (err) {
      setError(err.message || 'Failed to cancel')
    }
  }

  const handleBackfill = async () => {
    if (!connectionId) return

    setBackfilling(true)
    setError(null)
    setBackfillResult(null)
    try {
      const result = await brainAPI.backfillQueryIntelligence(connectionId)
      setBackfillResult(result)

      // Refresh data to show new results
      if (result.executionsRecorded > 0) {
        await fetchData()
      }
    } catch (err) {
      setError(err.message || 'Failed to backfill from slow query history')
    } finally {
      setBackfilling(false)
    }
  }

  const hasNoData = !accuracy?.totalEstimates && patterns.length === 0

  const getAccuracyColor = (value) => {
    if (!value && value !== 0) return 'var(--color-light-6)'
    if (value >= 0.9) return 'var(--color-success)'
    if (value >= 0.7) return 'var(--color-warning)'
    return 'var(--color-danger)'
  }

  const formatPercentage = (value) => {
    if (!value && value !== 0) return '-'
    return `${(value * 100).toFixed(1)}%`
  }

  const formatNumber = (num) => {
    if (!num && num !== 0) return '-'
    if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`
    if (num >= 1000) return `${(num / 1000).toFixed(1)}K`
    return num.toString()
  }

  const handleCopySql = async (sql, index) => {
    try {
      await navigator.clipboard.writeText(sql)
      setCopiedSql(index)
      setTimeout(() => setCopiedSql(null), 2000)
    } catch {
      // Fallback for older browsers
      const textarea = document.createElement('textarea')
      textarea.value = sql
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      setCopiedSql(index)
      setTimeout(() => setCopiedSql(null), 2000)
    }
  }

  const handlePatternFeedback = async (patternId, wasSuccessful) => {
    try {
      await brainAPI.recordPatternFeedback(patternId, wasSuccessful)
      // Update local state to show feedback recorded
      setPatterns(prev => prev.map(p =>
        p.id === patternId
          ? { ...p, userFeedback: wasSuccessful ? 'success' : 'failed' }
          : p
      ))
    } catch (err) {
      setError('Failed to record feedback: ' + (err.message || 'Unknown error'))
    }
  }

  const tabs = [
    { id: 'accuracy', label: 'Cardinality Accuracy', icon: <Target size={14} />, helpContent: QUERY_INTELLIGENCE.accuracyTab },
    { id: 'patterns', label: 'Plan Patterns', icon: <Lightbulb size={14} />, helpContent: QUERY_INTELLIGENCE.patternsTab }
  ]

  return (
    <div className={styles.brainPanel}>
      <div className={styles.brainHeader}>
        <div>
          <h3>Query Intelligence</h3>
          <p>ML-based learning for cardinality accuracy and query plan patterns</p>
          <p style={{ fontSize: '11px', color: 'var(--color-light-6)', marginTop: '2px' }}>
            For immediate slow query analysis and optimization, see Database Advisor → Slow Queries
          </p>
        </div>
        <div className={styles.brainHeaderActions}>
          <HelpTooltip content={QUERY_INTELLIGENCE.refreshButton}>
            <button
              className={styles.secondaryButton}
              onClick={fetchData}
              disabled={loading || backfilling}
            >
              <RefreshCw size={14} className={loading ? styles.spinner : ''} />
            </button>
          </HelpTooltip>
          {hasNoData && (
            <ActionGuard action="backfill-query-intelligence">
              <HelpTooltip content={{
                title: 'Learn from Slow Query History',
                description: 'Populate Query Intelligence by analyzing existing slow query history. This imports execution data to track cardinality accuracy and plan patterns.',
              }}>
                <button
                  className={styles.profileButton}
                  onClick={handleBackfill}
                  disabled={backfilling}
                >
                  {backfilling ? <Loader className={styles.spinner} size={14} /> : <Database size={14} />}
                  {backfilling ? 'Learning...' : 'Learn from History'}
                </button>
              </HelpTooltip>
            </ActionGuard>
          )}
        </div>
      </div>

      {/* Backfill Result Message */}
      {backfillResult && (
        <div style={{
          marginBottom: '16px',
          padding: '12px 16px',
          background: backfillResult.executionsRecorded > 0 ? 'var(--color-success-soft)' : 'var(--color-warning-soft)',
          borderRadius: '8px',
          fontSize: '13px',
          color: backfillResult.executionsRecorded > 0 ? 'var(--color-success)' : 'var(--color-warning)',
          display: 'flex',
          alignItems: 'center',
          gap: '8px'
        }}>
          {backfillResult.executionsRecorded > 0 ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
          <span>{backfillResult.message}</span>
          <button
            onClick={() => setBackfillResult(null)}
            style={{
              marginLeft: 'auto',
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              padding: '2px',
              color: 'inherit',
              opacity: 0.7
            }}
          >
            ×
          </button>
        </div>
      )}

      {error && (
        <div className={styles.brainError}>
          <AlertCircle size={14} />
          <span>{error}</span>
        </div>
      )}

      {/* Tab Navigation */}
      <div className={styles.brainTabs}>
        {tabs.map((tab) => (
          <HelpTooltip key={tab.id} content={tab.helpContent}>
            <button
              onClick={() => setActiveTab(tab.id)}
              className={`${styles.brainTab} ${activeTab === tab.id ? styles.brainTabActive : ''}`}
            >
              {tab.icon}
              {tab.label}
            </button>
          </HelpTooltip>
        ))}
      </div>

      {loading && (
        <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
          <Loader className={styles.spinner} size={24} />
          <p style={{ marginTop: '12px' }}>Loading query intelligence...</p>
        </div>
      )}

      {/* Cardinality Accuracy Tab */}
      {!loading && activeTab === 'accuracy' && (
        <div>
          {/* EXPLAIN Backfill Progress */}
          {explainProgress?.running && (
            <div style={{
              marginBottom: '16px',
              padding: '16px',
              background: 'var(--color-primary-soft)',
              borderRadius: '8px',
              border: '1px solid var(--color-primary-light)'
            }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <Loader size={18} className={styles.spinner} style={{ color: 'var(--color-primary)' }} />
                  <span style={{ fontWeight: 600, color: 'var(--color-primary-dark)' }}>
                    Calculating EXPLAIN Data...
                  </span>
                </div>
                <button
                  onClick={handleCancelExplain}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '4px',
                    padding: '6px 12px',
                    background: 'var(--color-danger-soft)',
                    color: 'var(--color-danger-text)',
                    border: 'none',
                    borderRadius: '6px',
                    fontSize: '12px',
                    fontWeight: 500,
                    cursor: 'pointer'
                  }}
                >
                  <StopCircle size={14} />
                  Cancel
                </button>
              </div>
              <div style={{
                height: '8px',
                background: 'var(--color-primary-light)',
                borderRadius: '4px',
                overflow: 'hidden',
                marginBottom: '8px'
              }}>
                <div style={{
                  width: `${explainProgress.total > 0 ? (explainProgress.processed / explainProgress.total) * 100 : 0}%`,
                  height: '100%',
                  background: 'var(--color-primary)',
                  borderRadius: '4px',
                  transition: 'width 0.3s ease'
                }} />
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px', color: 'var(--color-primary-dark)' }}>
                <span>
                  {formatNumber(explainProgress.processed)} of {formatNumber(explainProgress.total)} queries
                </span>
                <span>
                  {explainProgress.total > 0 ? Math.round((explainProgress.processed / explainProgress.total) * 100) : 0}%
                </span>
              </div>
              {explainProgress.errors > 0 && (
                <div style={{ marginTop: '8px', fontSize: '12px', color: 'var(--color-danger)' }}>
                  {explainProgress.errors} queries could not be explained
                  {explainProgress.nonExplainableQueries > 0 && (
                    <span style={{ color: 'var(--color-warning)' }}> ({explainProgress.nonExplainableQueries} non-SELECT)</span>
                  )}
                  {explainProgress.connectionErrors > 0 && (
                    <span style={{ color: 'var(--color-danger-text)' }}> ({explainProgress.connectionErrors} connection errors)</span>
                  )}
                </div>
              )}
            </div>
          )}

          {/* EXPLAIN completed message */}
          {explainProgress && !explainProgress.running && explainProgress.total > 0 && (
            <div style={{
              marginBottom: '16px',
              padding: '12px 16px',
              background: explainProgress.successful > 0 ? 'var(--color-success-soft)' : 'var(--color-warning-soft)',
              borderRadius: '8px',
              fontSize: '13px',
              color: explainProgress.successful > 0 ? 'var(--color-success)' : 'var(--color-warning)'
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                {explainProgress.successful > 0 ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
                <span style={{ flex: 1 }}>
                  EXPLAIN calculation complete. {formatNumber(explainProgress.successful)} successful
                  {explainProgress.errors > 0 && `, ${explainProgress.errors} failed`}.
                </span>
                <button
                  onClick={() => setExplainProgress(null)}
                  style={{
                    background: 'none',
                    border: 'none',
                    cursor: 'pointer',
                    padding: '2px',
                    color: 'inherit',
                    opacity: 0.7
                  }}
                >
                  ×
                </button>
              </div>

              {/* Show failure breakdown if there are errors */}
              {explainProgress.errors > 0 && (
                <div style={{ marginTop: '12px', paddingTop: '12px', borderTop: '1px solid var(--color-light-3)' }}>
                  <div style={{ fontWeight: 600, marginBottom: '8px' }}>Failure Breakdown:</div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginBottom: '8px' }}>
                    {explainProgress.nonExplainableQueries > 0 && (
                      <span style={{ padding: '4px 8px', background: 'var(--color-light-2)', borderRadius: '4px', fontSize: '12px' }}>
                        {explainProgress.nonExplainableQueries} non-SELECT queries
                      </span>
                    )}
                    {explainProgress.connectionErrors > 0 && (
                      <span style={{ padding: '4px 8px', background: 'var(--color-danger-soft)', borderRadius: '4px', fontSize: '12px', color: 'var(--color-danger-text)' }}>
                        {explainProgress.connectionErrors} connection errors
                      </span>
                    )}
                    {explainProgress.parseErrors > 0 && (
                      <span style={{ padding: '4px 8px', background: 'var(--color-light-2)', borderRadius: '4px', fontSize: '12px' }}>
                        {explainProgress.parseErrors} parse errors
                      </span>
                    )}
                    {explainProgress.noRowEstimateQueries > 0 && (
                      <span style={{ padding: '4px 8px', background: 'var(--color-light-2)', borderRadius: '4px', fontSize: '12px' }}>
                        {explainProgress.noRowEstimateQueries} no row estimates
                      </span>
                    )}
                  </div>

                  {/* Show sample failures */}
                  {explainProgress.sampleFailures && explainProgress.sampleFailures.length > 0 && (
                    <div style={{ marginTop: '8px' }}>
                      <div style={{ fontSize: '12px', fontWeight: 500, marginBottom: '4px' }}>Sample failures:</div>
                      <div style={{
                        fontSize: '11px',
                        fontFamily: 'monospace',
                        background: 'rgba(0,0,0,0.05)',
                        padding: '8px',
                        borderRadius: '4px',
                        maxHeight: '100px',
                        overflow: 'auto'
                      }}>
                        {explainProgress.sampleFailures.map((failure, idx) => (
                          <div key={idx} style={{ marginBottom: idx < explainProgress.sampleFailures.length - 1 ? '4px' : 0 }}>
                            • {failure}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Show tracking status when we have executions but no accuracy data */}
          {accuracy?.needsExplainData && !explainProgress?.running && (
            <div style={{
              marginBottom: '16px',
              padding: '16px',
              background: 'var(--color-primary-soft)',
              borderRadius: '8px',
              border: '1px solid var(--color-primary-light)'
            }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <Database size={18} style={{ color: 'var(--color-primary)' }} />
                  <span style={{ fontWeight: 600, color: 'var(--color-primary-dark)' }}>
                    Tracking {formatNumber(accuracy.totalExecutions)} Query Executions
                  </span>
                </div>
                <ActionGuard action="calculate-explain-data">
                  <HelpTooltip content={{
                    title: 'Calculate EXPLAIN Data',
                    description: 'Run EXPLAIN on tracked queries to capture optimizer row estimates. This allows calculating cardinality accuracy by comparing estimates vs actual rows.',
                    recommendation: 'Runs in small batches with delays to avoid impacting database performance.'
                  }}>
                    <button
                      onClick={handleStartExplainBackfill}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '6px',
                        padding: '8px 14px',
                        background: 'var(--color-primary)',
                        color: 'white',
                        border: 'none',
                        borderRadius: '6px',
                        fontSize: '13px',
                        fontWeight: 500,
                        cursor: 'pointer',
                        transition: 'background 0.2s'
                      }}
                    >
                      <Play size={14} />
                      Calculate EXPLAIN Data
                    </button>
                  </HelpTooltip>
                </ActionGuard>
              </div>
              <p style={{ fontSize: '13px', color: 'var(--color-primary-dark)', margin: 0 }}>
                Executions are being tracked. Click &quot;Calculate EXPLAIN Data&quot; to run EXPLAIN plans
                and enable cardinality accuracy metrics.
              </p>
            </div>
          )}

          {!accuracy?.totalEstimates && !accuracy?.needsExplainData ? (
            <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
              <Target size={32} style={{ marginBottom: '12px', opacity: 0.5 }} />
              <p style={{ fontWeight: 500, marginBottom: '8px' }}>No cardinality accuracy data yet</p>
              <p style={{ fontSize: '13px', maxWidth: '400px', margin: '0 auto' }}>
                Data is collected automatically when slow queries are ingested.
                Click &quot;Learn from History&quot; above to populate from existing slow query analyses.
              </p>
            </div>
          ) : !accuracy?.totalEstimates ? (
            <div style={{ textAlign: 'center', padding: '24px', color: 'var(--color-light-6)' }}>
              <p style={{ fontSize: '13px' }}>
                Waiting for EXPLAIN data to calculate accuracy metrics...
              </p>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
              {/* Overall Accuracy */}
              <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
                gap: '16px'
              }}>
                <HelpTooltip content={QUERY_INTELLIGENCE.accuracy.overallAccuracy}>
                  <AccuracyCard
                    icon={<Target size={20} />}
                    label="Overall Accuracy"
                    value={formatPercentage(accuracy.overallAccuracy)}
                    color={getAccuracyColor(accuracy.overallAccuracy)}
                  />
                </HelpTooltip>
                <HelpTooltip content={QUERY_INTELLIGENCE.accuracy.accurateEstimates}>
                  <AccuracyCard
                    icon={<CheckCircle size={20} />}
                    label="Accurate Estimates"
                    value={formatNumber(accuracy.accurateEstimates)}
                    subValue={`of ${formatNumber(accuracy.totalEstimates)}`}
                    color="var(--color-success)"
                  />
                </HelpTooltip>
                <HelpTooltip content={QUERY_INTELLIGENCE.accuracy.overestimates}>
                  <AccuracyCard
                    icon={<XCircle size={20} />}
                    label="Overestimates"
                    value={formatNumber(accuracy.overestimates)}
                    color="var(--color-warning)"
                  />
                </HelpTooltip>
                <HelpTooltip content={QUERY_INTELLIGENCE.accuracy.underestimates}>
                  <AccuracyCard
                    icon={<TrendingUp size={20} />}
                    label="Underestimates"
                    value={formatNumber(accuracy.underestimates)}
                    color="var(--color-danger)"
                  />
                </HelpTooltip>
              </div>

              {/* Per-Table Accuracy */}
              {accuracy.perTableAccuracy && Object.keys(accuracy.perTableAccuracy).length > 0 && (
                <div>
                  <HelpTooltip content={QUERY_INTELLIGENCE.accuracy.perTableAccuracy}>
                    <h4 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '12px', cursor: 'help', display: 'inline-block' }}>
                      Accuracy by Table
                    </h4>
                  </HelpTooltip>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {Object.entries(accuracy.perTableAccuracy)
                      .sort(([, a], [, b]) => b - a)
                      .slice(0, 10)
                      .map(([table, acc]) => (
                        <div key={table} style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '12px',
                          padding: '8px 12px',
                          background: 'var(--color-light-1)',
                          borderRadius: '6px'
                        }}>
                          <span style={{ flex: 1, fontWeight: 500 }}>{table}</span>
                          <div style={{ width: '120px', height: '8px', background: 'var(--color-light-2)', borderRadius: '4px', overflow: 'hidden' }}>
                            <div style={{
                              width: `${acc * 100}%`,
                              height: '100%',
                              background: getAccuracyColor(acc),
                              borderRadius: '4px'
                            }} />
                          </div>
                          <span style={{ width: '50px', textAlign: 'right', fontSize: '13px', fontWeight: 500, color: getAccuracyColor(acc) }}>
                            {formatPercentage(acc)}
                          </span>
                        </div>
                      ))}
                  </div>
                </div>
              )}

              {/* Recommendations - Actionable insights for improving cardinality accuracy */}
              {accuracy.recommendations && accuracy.recommendations.length > 0 && (
                <div style={{ marginTop: '8px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                    <Wrench size={16} style={{ color: 'var(--color-primary)' }} />
                    <h4 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                      Recommendations
                    </h4>
                    <span style={{
                      fontSize: '11px',
                      padding: '2px 6px',
                      background: 'var(--color-primary-light)',
                      color: 'var(--color-primary-dark)',
                      borderRadius: '4px'
                    }}>
                      {accuracy.recommendations.length}
                    </span>
                  </div>
                  <p style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '12px' }}>
                    These recommendations focus on improving MySQL optimizer statistics.
                    For query-specific optimizations, see Database Advisor → Slow Queries.
                  </p>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                    {accuracy.recommendations.map((rec, idx) => (
                      <div key={idx} style={{
                        padding: '16px',
                        background: 'var(--color-light-1)',
                        borderRadius: '8px',
                        borderLeft: `4px solid ${rec.priority === 'HIGH' ? 'var(--color-danger)' : 'var(--color-warning)'}`
                      }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
                          <span style={{
                            fontSize: '11px',
                            padding: '2px 8px',
                            background: rec.priority === 'HIGH' ? 'var(--color-danger-soft)' : 'var(--color-warning-soft)',
                            color: rec.priority === 'HIGH' ? 'var(--color-danger)' : 'var(--color-warning)',
                            borderRadius: '4px',
                            fontWeight: 600
                          }}>
                            {rec.priority}
                          </span>
                          <span style={{ fontWeight: 600, fontSize: '14px' }}>{rec.title}</span>
                        </div>

                        <p style={{
                          fontSize: '13px',
                          color: 'var(--color-dark-1)',
                          marginBottom: '12px',
                          lineHeight: '1.5'
                        }}>
                          {rec.description}
                        </p>

                        {rec.sql && (
                          <div style={{ marginBottom: '12px' }}>
                            <div style={{
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'space-between',
                              marginBottom: '6px'
                            }}>
                              <span style={{ fontSize: '12px', fontWeight: 500, color: 'var(--color-light-6)' }}>
                                SQL Command
                              </span>
                              <button
                                onClick={() => handleCopySql(rec.sql, idx)}
                                style={{
                                  display: 'flex',
                                  alignItems: 'center',
                                  gap: '4px',
                                  padding: '4px 8px',
                                  background: copiedSql === idx ? 'var(--color-success-soft)' : 'var(--color-light-2)',
                                  color: copiedSql === idx ? 'var(--color-success)' : 'var(--color-dark-1)',
                                  border: 'none',
                                  borderRadius: '4px',
                                  fontSize: '11px',
                                  fontWeight: 500,
                                  cursor: 'pointer',
                                  transition: 'all 0.2s ease'
                                }}
                              >
                                {copiedSql === idx ? <Check size={12} /> : <Copy size={12} />}
                                {copiedSql === idx ? 'Copied!' : 'Copy'}
                              </button>
                            </div>
                            <pre style={{
                              padding: '12px',
                              background: 'var(--color-dark-1)',
                              color: 'var(--color-light-3)',
                              borderRadius: '6px',
                              fontSize: '12px',
                              fontFamily: 'monospace',
                              overflow: 'auto',
                              margin: 0,
                              whiteSpace: 'pre-wrap',
                              wordBreak: 'break-all'
                            }}>
                              {rec.sql}
                            </pre>
                          </div>
                        )}

                        {rec.impact && (
                          <div style={{
                            padding: '10px 12px',
                            background: 'var(--color-success-soft)',
                            borderRadius: '6px',
                            fontSize: '12px',
                            color: 'var(--color-success)',
                            display: 'flex',
                            alignItems: 'flex-start',
                            gap: '8px'
                          }}>
                            <TrendingUp size={14} style={{ flexShrink: 0, marginTop: '1px' }} />
                            <span><strong>Expected Impact:</strong> {rec.impact}</span>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Plan Patterns Tab */}
      {!loading && activeTab === 'patterns' && (
        <div>
          {/* Pattern Stats Summary */}
          {patternStats && (
            <div style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))',
              gap: '12px',
              marginBottom: '20px'
            }}>
              <HelpTooltip content={QUERY_INTELLIGENCE.patterns.totalPatterns}>
                <div style={{ padding: '12px', background: 'var(--color-light-1)', borderRadius: '6px', textAlign: 'center', cursor: 'help' }}>
                  <div style={{ fontSize: '24px', fontWeight: 600, color: 'var(--color-primary)' }}>
                    {formatNumber(patternStats.totalPatterns)}
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>Total Patterns</div>
                </div>
              </HelpTooltip>
              <HelpTooltip content={QUERY_INTELLIGENCE.patterns.reliablePatterns}>
                <div style={{ padding: '12px', background: 'var(--color-light-1)', borderRadius: '6px', textAlign: 'center', cursor: 'help' }}>
                  <div style={{ fontSize: '24px', fontWeight: 600, color: 'var(--color-success)' }}>
                    {formatNumber(patternStats.reliablePatterns)}
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>Reliable</div>
                </div>
              </HelpTooltip>
              <HelpTooltip content={QUERY_INTELLIGENCE.patterns.avgConfidence}>
                <div style={{ padding: '12px', background: 'var(--color-light-1)', borderRadius: '6px', textAlign: 'center', cursor: 'help' }}>
                  <div style={{ fontSize: '24px', fontWeight: 600, color: 'var(--color-warning)' }}>
                    {patternStats.averageEffectiveness != null ? `${patternStats.averageEffectiveness.toFixed(1)}%` : '-'}
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>Avg Effectiveness</div>
                </div>
              </HelpTooltip>
            </div>
          )}

          {patterns.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
              <Lightbulb size={32} style={{ marginBottom: '12px', opacity: 0.5 }} />
              <p style={{ fontWeight: 500, marginBottom: '8px' }}>No plan patterns available</p>
              <p style={{ fontSize: '13px', maxWidth: '450px', margin: '0 auto', lineHeight: '1.5' }}>
                Plan patterns require <strong>SELECT queries</strong> in your slow query history.
                INSERT/UPDATE statements don&apos;t generate meaningful execution plans.
                {' '}Click &quot;Learn from History&quot; to analyze existing SELECT queries,
                or ensure your slow query log captures SELECT statements.
              </p>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {patterns.map((pattern, idx) => (
                <div key={idx} style={{
                  padding: '16px',
                  background: 'var(--color-light-1)',
                  borderRadius: '8px',
                  border: '1px solid var(--color-light-2)'
                }}>
                  <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '8px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <Lightbulb size={16} style={{ color: 'var(--color-warning)' }} />
                      <HelpTooltip content={QUERY_INTELLIGENCE.patterns.patternType}>
                        <span style={{ fontWeight: 600, cursor: 'help' }}>{pattern.patternType || 'Query Pattern'}</span>
                      </HelpTooltip>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      {/* Compute reliability client-side: usageCount >= 5 && effectivenessScore >= 60 */}
                      {(() => {
                        const isReliable = (pattern.usageCount >= 5) && (pattern.effectivenessScore != null && pattern.effectivenessScore >= 60)
                        return (
                          <span style={{
                            fontSize: '11px',
                            padding: '2px 6px',
                            background: isReliable ? 'var(--color-success-soft)' : 'var(--color-warning-soft)',
                            color: isReliable ? 'var(--color-success)' : 'var(--color-warning)',
                            borderRadius: '4px',
                            fontWeight: 500
                          }}>
                            {isReliable ? 'Reliable' : 'Learning'}
                          </span>
                        )
                      })()}
                      <HelpTooltip content={QUERY_INTELLIGENCE.patterns.observationCount}>
                        <span style={{ fontSize: '12px', color: 'var(--color-light-6)', cursor: 'help' }}>
                          {pattern.usageCount || 0} uses
                        </span>
                      </HelpTooltip>
                    </div>
                  </div>
                  {pattern.queryPattern && (
                    <pre style={{
                      padding: '12px',
                      background: 'var(--color-light-2)',
                      borderRadius: '4px',
                      fontSize: '12px',
                      fontFamily: 'monospace',
                      overflow: 'auto',
                      maxHeight: '100px',
                      margin: 0
                    }}>
                      {pattern.queryPattern}
                    </pre>
                  )}
                  {pattern.tablesInvolved && pattern.tablesInvolved.length > 0 && (
                    <div style={{ marginTop: '8px', fontSize: '12px', color: 'var(--color-light-6)' }}>
                      <strong>Tables:</strong> {pattern.tablesInvolved.join(', ')}
                    </div>
                  )}
                  {pattern.suggestions && pattern.suggestions.length > 0 && (
                    <div style={{ marginTop: '12px' }}>
                      <div style={{ fontSize: '12px', fontWeight: 600, marginBottom: '8px', color: 'var(--color-dark-1)' }}>
                        Optimization Suggestions:
                      </div>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                        {pattern.suggestions.slice(0, 3).map((suggestion, sIdx) => (
                          <div key={sIdx} style={{
                            padding: '12px',
                            background: suggestion.severity === 'HIGH' ? 'var(--color-danger-soft)' :
                                       suggestion.severity === 'MEDIUM' ? 'var(--color-warning-soft)' : 'var(--color-primary-soft)',
                            borderLeft: `3px solid ${
                              suggestion.severity === 'HIGH' ? 'var(--color-danger)' :
                              suggestion.severity === 'MEDIUM' ? 'var(--color-warning)' : 'var(--color-primary)'
                            }`,
                            borderRadius: '4px'
                          }}>
                            {/* Header with type and priority */}
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                              <span style={{
                                fontSize: '10px',
                                padding: '2px 6px',
                                background: 'rgba(0,0,0,0.1)',
                                borderRadius: '3px',
                                fontWeight: 600,
                                textTransform: 'uppercase'
                              }}>
                                {suggestion.type || 'ISSUE'}
                              </span>
                              {suggestion.priority && (
                                <span style={{ fontSize: '11px', color: 'var(--color-light-6)' }}>
                                  Priority: {suggestion.priority}
                                </span>
                              )}
                              {suggestion.estimatedImprovement && (
                                <span style={{
                                  fontSize: '11px',
                                  color: 'var(--color-success)',
                                  marginLeft: 'auto'
                                }}>
                                  +{suggestion.estimatedImprovement}% expected
                                </span>
                              )}
                            </div>

                            {/* Message / Description */}
                            <div style={{ fontWeight: 500, marginBottom: '4px', fontSize: '13px' }}>
                              {suggestion.message || suggestion.type}
                            </div>

                            {/* Recommendation */}
                            {suggestion.recommendation && (
                              <div style={{ color: 'var(--color-light-6)', fontSize: '12px', marginBottom: '8px' }}>
                                {suggestion.recommendation}
                              </div>
                            )}

                            {/* Reasoning (for AI-generated suggestions) */}
                            {suggestion.reasoning && (
                              <div style={{
                                fontSize: '12px',
                                color: 'var(--color-dark-1)',
                                fontStyle: 'italic',
                                marginBottom: '8px'
                              }}>
                                {suggestion.reasoning}
                              </div>
                            )}

                            {/* SQL Code Block */}
                            {(suggestion.sql || suggestion.suggestedSQL) && (
                              <div style={{ marginTop: '8px' }}>
                                <div style={{
                                  display: 'flex',
                                  alignItems: 'center',
                                  justifyContent: 'space-between',
                                  marginBottom: '4px'
                                }}>
                                  <span style={{ fontSize: '11px', fontWeight: 500, color: 'var(--color-light-6)' }}>
                                    {suggestion.type === 'INDEX' ? 'Create Index' : 'SQL'}
                                  </span>
                                  <button
                                    onClick={() => handleCopySql(suggestion.sql || suggestion.suggestedSQL, `pattern-${idx}-${sIdx}`)}
                                    style={{
                                      display: 'flex',
                                      alignItems: 'center',
                                      gap: '4px',
                                      padding: '3px 6px',
                                      background: copiedSql === `pattern-${idx}-${sIdx}` ? 'var(--color-success-soft)' : 'var(--color-light-2)',
                                      color: copiedSql === `pattern-${idx}-${sIdx}` ? 'var(--color-success)' : 'var(--color-dark-1)',
                                      border: 'none',
                                      borderRadius: '3px',
                                      fontSize: '10px',
                                      cursor: 'pointer'
                                    }}
                                  >
                                    {copiedSql === `pattern-${idx}-${sIdx}` ? <Check size={10} /> : <Copy size={10} />}
                                    {copiedSql === `pattern-${idx}-${sIdx}` ? 'Copied' : 'Copy'}
                                  </button>
                                </div>
                                <pre style={{
                                  padding: '8px',
                                  background: 'var(--color-dark-1)',
                                  color: 'var(--color-light-3)',
                                  borderRadius: '4px',
                                  fontSize: '11px',
                                  fontFamily: 'monospace',
                                  overflow: 'auto',
                                  margin: 0,
                                  whiteSpace: 'pre-wrap'
                                }}>
                                  {suggestion.sql || suggestion.suggestedSQL}
                                </pre>
                              </div>
                            )}

                            {/* Example Query Rewrite */}
                            {suggestion.example && suggestion.type === 'QUERY_REWRITE' && (
                              <div style={{ marginTop: '8px' }}>
                                <span style={{ fontSize: '11px', fontWeight: 500, color: 'var(--color-light-6)' }}>
                                  Optimized Query:
                                </span>
                                <pre style={{
                                  marginTop: '4px',
                                  padding: '8px',
                                  background: 'var(--color-success-soft)',
                                  color: 'var(--color-success)',
                                  borderRadius: '4px',
                                  fontSize: '11px',
                                  fontFamily: 'monospace',
                                  overflow: 'auto',
                                  margin: 0,
                                  whiteSpace: 'pre-wrap'
                                }}>
                                  {suggestion.example}
                                </pre>
                              </div>
                            )}

                            {/* Index columns for INDEX type */}
                            {suggestion.columns && (
                              <div style={{ marginTop: '6px', fontSize: '11px' }}>
                                <span style={{ color: 'var(--color-light-6)' }}>Columns: </span>
                                <span style={{ fontFamily: 'monospace' }}>
                                  {Array.isArray(suggestion.columns) ? suggestion.columns.join(', ') : suggestion.columns}
                                </span>
                              </div>
                            )}
                          </div>
                        ))}
                        {pattern.suggestions.length > 3 && (
                          <div style={{ fontSize: '11px', color: 'var(--color-light-6)', fontStyle: 'italic' }}>
                            +{pattern.suggestions.length - 3} more suggestions
                          </div>
                        )}
                      </div>
                    </div>
                  )}

                  {/* Show optimized query if available at pattern level */}
                  {pattern.optimizedQuery && (
                    <div style={{ marginTop: '12px' }}>
                      <div style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        marginBottom: '4px'
                      }}>
                        <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--color-success)' }}>
                          Optimized Query Available
                        </span>
                        <button
                          onClick={() => handleCopySql(pattern.optimizedQuery, `optimized-${idx}`)}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px',
                            padding: '4px 8px',
                            background: copiedSql === `optimized-${idx}` ? 'var(--color-success-soft)' : 'var(--color-light-2)',
                            color: copiedSql === `optimized-${idx}` ? 'var(--color-success)' : 'var(--color-dark-1)',
                            border: 'none',
                            borderRadius: '4px',
                            fontSize: '11px',
                            cursor: 'pointer'
                          }}
                        >
                          {copiedSql === `optimized-${idx}` ? <Check size={12} /> : <Copy size={12} />}
                          {copiedSql === `optimized-${idx}` ? 'Copied' : 'Copy'}
                        </button>
                      </div>
                      <pre style={{
                        padding: '10px',
                        background: 'var(--color-success-soft)',
                        border: '1px solid var(--color-success)',
                        color: 'var(--color-dark-1)',
                        borderRadius: '6px',
                        fontSize: '12px',
                        fontFamily: 'monospace',
                        overflow: 'auto',
                        margin: 0,
                        whiteSpace: 'pre-wrap'
                      }}>
                        {pattern.optimizedQuery}
                      </pre>
                    </div>
                  )}

                  {/* Explanation */}
                  {pattern.explanation && (
                    <div style={{
                      marginTop: '12px',
                      padding: '10px',
                      background: 'var(--color-light-2)',
                      borderRadius: '6px',
                      fontSize: '12px',
                      color: 'var(--color-dark-1)',
                      whiteSpace: 'pre-wrap'
                    }}>
                      {pattern.explanation}
                    </div>
                  )}

                  {/* Feedback Buttons */}
                  {pattern.suggestions && pattern.suggestions.length > 0 && (
                    <div style={{
                      marginTop: '12px',
                      paddingTop: '12px',
                      borderTop: '1px solid var(--color-light-2)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between'
                    }}>
                      <div style={{ fontSize: '11px', color: 'var(--color-light-6)' }}>
                        Was this optimization helpful?
                      </div>
                      <div style={{ display: 'flex', gap: '8px' }}>
                        <button
                          onClick={() => handlePatternFeedback(pattern.id, true)}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px',
                            padding: '4px 8px',
                            background: pattern.userFeedback === 'success' ? 'var(--color-success-soft)' : 'var(--color-light-2)',
                            color: pattern.userFeedback === 'success' ? 'var(--color-success)' : 'var(--color-dark-1)',
                            border: 'none',
                            borderRadius: '4px',
                            fontSize: '11px',
                            cursor: 'pointer'
                          }}
                        >
                          <ThumbsUp size={12} />
                          Helpful
                        </button>
                        <button
                          onClick={() => handlePatternFeedback(pattern.id, false)}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px',
                            padding: '4px 8px',
                            background: pattern.userFeedback === 'failed' ? 'var(--color-danger-soft)' : 'var(--color-light-2)',
                            color: pattern.userFeedback === 'failed' ? 'var(--color-danger)' : 'var(--color-dark-1)',
                            border: 'none',
                            borderRadius: '4px',
                            fontSize: '11px',
                            cursor: 'pointer'
                          }}
                        >
                          <ThumbsDown size={12} />
                          Not helpful
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function AccuracyCard({ icon, label, value, subValue, color }) {
  return (
    <div style={{
      padding: '16px',
      background: 'var(--color-light-1)',
      borderRadius: '8px',
      borderTop: `3px solid ${color}`
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px', color }}>
        {icon}
        <span style={{ fontSize: '13px', color: 'var(--color-light-6)' }}>{label}</span>
      </div>
      <div style={{ fontSize: '24px', fontWeight: 600 }}>{value}</div>
      {subValue && <div style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>{subValue}</div>}
    </div>
  )
}

export default QueryIntelligencePanel
