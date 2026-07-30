'use client'

import { useState, useEffect } from 'react'
import {
  Zap,
  RefreshCw,
  Settings,
  Layers,
  Wrench,
  X,
  CheckCircle,
  Loader2,
} from 'lucide-react'
import { performanceActionsAPI } from '@/lib/api/client'
import { useOptimizationCandidates, useBenchmarkCandidates } from '@/lib/hooks/queries'
import styles from './PerformanceActionCard.module.css'

/**
 * Category icons and labels - grayscale design
 */
const CATEGORY_CONFIG = {
  INDEX: { icon: Zap, label: 'INDEX', color: '#374151' },
  QUERY_REWRITE: { icon: RefreshCw, label: 'QUERY', color: '#4B5563' },
  CONFIG: { icon: Settings, label: 'CONFIG', color: '#6B7280' },
  SCHEMA: { icon: Layers, label: 'SCHEMA', color: '#374151' },
  MAINTENANCE: { icon: Wrench, label: 'MAINT', color: '#4B5563' },
}

/**
 * Helper to parse usage metrics from description string
 */
function parseUsageMetrics(description) {
  if (!description) return null
  
  // Format: "Column {table}.{column} is used in {total} queries (WHERE: {where}, JOIN: {join}, ORDER BY: {orderby}) but has no index"
  const match = description.match(/WHERE:\s*(\d+),\s*JOIN:\s*(\d+),\s*ORDER BY:\s*(\d+)/i)
  
  if (match) {
    return {
      where: parseInt(match[1], 10),
      join: parseInt(match[2], 10),
      orderBy: parseInt(match[3], 10)
    }
  }
  return null
}

/**
 * PerformanceActionCard - Displays a unified performance action with redesigned layout.
 * Highlights Target, Usage, and Benefit.
 */
export default function PerformanceActionCard({
  action,
  index,
  onDismiss,
  connectionId,
  variant = 'default', // 'default' | 'compact'
}) {
  const [showQueriesModal, setShowQueriesModal] = useState(false)
  const [affectedQueries, setAffectedQueries] = useState(null)
  const [loadingQueries, setLoadingQueries] = useState(false)
  const [queriesError, setQueriesError] = useState(null)
  const [activeQueryTab, setActiveQueryTab] = useState('rewrite')
  const [benchmarkTriggered, setBenchmarkTriggered] = useState(false)

  useEffect(() => {
    if (!showQueriesModal || !action?.id) return
    setLoadingQueries(true)
    setQueriesError(null)
    setAffectedQueries(null)
    performanceActionsAPI
      .getAffectedQueries(action.id)
      .then((data) => setAffectedQueries(data))
      .catch((err) => setQueriesError(err?.message || 'Failed to load queries'))
      .finally(() => setLoadingQueries(false))
  }, [showQueriesModal, action?.id])

  const categoryConfig = CATEGORY_CONFIG[action.category] || CATEGORY_CONFIG.INDEX
  const CategoryIcon = categoryConfig.icon

  const formattedIndex = String(index).padStart(3, '0')

  const isQueryRewrite = action.category === 'QUERY_REWRITE'
  const queryFingerprint = isQueryRewrite ? action.targetObject : null
  
  const { data: candidatesData, refetch: refetchCandidates } = useOptimizationCandidates(
    connectionId,
    queryFingerprint
  )
  const benchmarkCandidates = useBenchmarkCandidates()
  const candidates = candidatesData?.candidates || []
  const baselineCandidate = candidates.find((c) => c.candidateId === 'ORIGINAL')
  const rewriteCandidate = candidates.find((c) => c.candidateId === 'AI_REWRITE')
  const baselineRuntime = baselineCandidate?.medianMs ?? baselineCandidate?.benchmarkMs ?? null
  const rewriteRuntime = rewriteCandidate?.medianMs ?? rewriteCandidate?.benchmarkMs ?? null
  const baselineMeasured = baselineRuntime != null
  const rewriteMeasured = rewriteRuntime != null
  const deltaPct = baselineMeasured && rewriteMeasured && baselineRuntime > 0
    ? ((baselineRuntime - rewriteRuntime) / baselineRuntime) * 100
    : null

  const originalSql = baselineCandidate?.candidateSql || null
  const rewrittenSql = rewriteCandidate?.candidateSql || action.optimizedQuery || null

  // Parse metrics for Key Column Analysis
  const usageMetrics = parseUsageMetrics(action.description)
  const impactLabel = action.impactScore >= 70 ? 'High' : action.impactScore >= 40 ? 'Medium' : 'Low'

  useEffect(() => {
    if (activeQueryTab === 'rewrite' && !rewrittenSql && originalSql) {
      setActiveQueryTab('original')
    } else if (activeQueryTab === 'original' && !originalSql && rewrittenSql) {
      setActiveQueryTab('rewrite')
    }
  }, [activeQueryTab, originalSql, rewrittenSql])

  useEffect(() => {
    // Auto-trigger benchmark if expanded (now always visible) and query rewrite
    if (!isQueryRewrite || !connectionId || !queryFingerprint) {
      return
    }
    if (benchmarkTriggered || benchmarkCandidates.isPending) {
      return
    }
    if (!candidates.length) {
      return
    }
    if (baselineMeasured && rewriteMeasured) {
      return
    }

    setBenchmarkTriggered(true)
    benchmarkCandidates.mutateAsync({
      connectionId,
      queryFingerprint,
      runs: 3,
      timeoutMs: 30000,
    }).then(() => {
      refetchCandidates?.()
    }).catch(() => {
      // noop
    })
  }, [
    isQueryRewrite,
    connectionId,
    queryFingerprint,
    benchmarkTriggered,
    benchmarkCandidates,
    candidates.length,
    baselineMeasured,
    rewriteMeasured,
    refetchCandidates,
  ])

  const formatDuration = (ms) => {
    if (ms == null) return '—'
    if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
    return `${ms.toFixed(0)}ms`
  }

  const activeSql = activeQueryTab === 'original' ? originalSql : rewrittenSql
  const activeRuntime = activeQueryTab === 'original' ? baselineRuntime : rewriteRuntime

  // Construct Target Display (Table . Column)
  const targetDisplay = action.targetSecondary 
    ? `${action.targetObject}.${action.targetSecondary}`
    : action.targetObject

  return (
    <div className={styles.card}>
      {/* Header */}
      <div className={styles.header}>
        <div className={styles.headerLeft}>
          <span className={styles.index}>#{formattedIndex}</span>
          <span
            className={styles.categoryBadge}
            style={{ backgroundColor: categoryConfig.color }}
          >
            <CategoryIcon size={12} />
            {categoryConfig.label}
          </span>
          <span className={styles.title}>{action.title}</span>
        </div>
        
        {/* Top Right Metrics Section */}
        <div className={styles.headerRightMetrics}>
          {usageMetrics && (
            <>
              <div className={styles.metricItem}>
                <span className={styles.metricValue}>{usageMetrics.join}</span>
                <span className={styles.metricLabel}>Joins</span>
              </div>
              <div className={styles.metricItem}>
                <span className={styles.metricValue}>{usageMetrics.where}</span>
                <span className={styles.metricLabel}>Where</span>
              </div>
              <div className={styles.metricItem}>
                <span className={styles.metricValue}>{usageMetrics.orderBy}</span>
                <span className={styles.metricLabel}>Order By</span>
              </div>
            </>
          )}
          <div className={styles.metricItem}>
            <span className={`${styles.metricValue} ${styles.impactValue}`}>{impactLabel}</span>
            <span className={styles.metricLabel}>Impact</span>
          </div>
        </div>
      </div>

      {variant !== 'compact' ? (
        <div className={styles.mainContent}>
          {/* Target Section */}
          <div className={styles.targetSection}>
            <span className={styles.targetLabel}>TARGET</span>
            <code className={styles.targetValue}>{targetDisplay}</code>
          </div>

          {/* Usage / Description Section - Hide if metrics are parsed (shown in header) */}
          {action.description && !usageMetrics && (
            <div className={styles.usageSection}>
              <p className={styles.usageText}>{action.description}</p>
            </div>
          )}

          {/* Recommendation Content (Always Visible) */}
          <div className={styles.recommendationContent}>
            {/* SQL Statement */}
            {action.sqlStatement && (
              <div className={styles.codeBlock}>
                <div className={styles.codeLabel}>Recommendation</div>
                <code>{action.sqlStatement}</code>
              </div>
            )}

            {/* Optimized Query */}
            {isQueryRewrite && (
              <div className={styles.compareSection}>
                <div className={styles.compareHeader}>
                  <div className={styles.compareTabs}>
                    {originalSql && (
                      <button
                        className={`${styles.compareTab} ${activeQueryTab === 'original' ? styles.compareTabActive : ''}`}
                        onClick={() => setActiveQueryTab('original')}
                        type="button"
                      >
                        Original
                      </button>
                    )}
                    {rewrittenSql && (
                      <button
                        className={`${styles.compareTab} ${activeQueryTab === 'rewrite' ? styles.compareTabActive : ''}`}
                        onClick={() => setActiveQueryTab('rewrite')}
                        type="button"
                      >
                        Rewritten
                      </button>
                    )}
                  </div>
                  <div className={styles.compareStats}>
                    <span className={styles.compareStat}>Old: {formatDuration(baselineRuntime)}</span>
                    <span className={styles.compareStat}>New: {formatDuration(rewriteRuntime)}</span>
                    {deltaPct != null && (
                      <span className={`${styles.compareDelta} ${deltaPct >= 0 ? styles.deltaPositive : styles.deltaNegative}`}>
                        {deltaPct >= 0 ? '-' : '+'}{Math.abs(deltaPct).toFixed(1)}%
                      </span>
                    )}
                  </div>
                </div>

                <div className={styles.comparePanel}>
                  <div className={styles.compareMeta}>
                    <span>{activeQueryTab === 'original' ? 'Baseline Query' : 'Optimized Query'}</span>
                    <span>Runtime: {formatDuration(activeRuntime)}</span>
                  </div>
                  <pre className={styles.compareCode}>
                    <code>{activeSql || 'Query text not available.'}</code>
                  </pre>
                </div>
              </div>
            )}

            {!isQueryRewrite && action.optimizedQuery && (
              <div className={styles.codeBlock}>
                <div className={styles.codeLabel}>Optimized Query</div>
                <code>{action.optimizedQuery}</code>
              </div>
            )}

            {/* Config Value */}
            {action.configValue && (
              <div className={styles.configValue}>
                <span className={styles.configLabel}>Recommended Value:</span>
                <code>{action.configValue}</code>
              </div>
            )}
          </div>

          {/* Footer Actions (No Benefit Section anymore as metrics moved to top) */}
          <div className={styles.actionsRow}>
            {action.queriesAffected != null && (
                <button
                  type="button"
                  className={styles.actionButton}
                  onClick={() => setShowQueriesModal(true)}
                >
                  <Layers size={12} />
                  {action.queriesAffected.toLocaleString()} affected queries
                </button>
            )}
            
            {onDismiss && (
              <button
                className={styles.actionButton}
                onClick={() => onDismiss(action)}
              >
                <X size={12} />
                Dismiss
              </button>
            )}
          </div>
        </div>
      ) : (
        /* Compact Variant */
        <div className={styles.compactStats}>
           <div className={styles.compactStatRow}>
            <span className={styles.compactStatLabel}>Target</span>
            <code className={styles.compactStatValue}>{targetDisplay}</code>
          </div>
          {action.description && (
            <p className={styles.compactDescription}>{action.description}</p>
          )}
          <div className={styles.compactMetaRow}>
            <span className={styles.benefitValueSmall}>
               Impact: {impactLabel}
            </span>
          </div>
        </div>
      )}

      {/* Queries Modal */}
      {showQueriesModal && (
        <div
          className={styles.modalOverlay}
          onClick={() => setShowQueriesModal(false)}
          role="presentation"
        >
          <div
            className={styles.queriesModal}
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-labelledby="queries-modal-title"
          >
            <div className={styles.modalHeader}>
              <h2 id="queries-modal-title" className={styles.modalTitle}>
                Affected queries
              </h2>
              <button
                type="button"
                className={styles.modalClose}
                onClick={() => setShowQueriesModal(false)}
                aria-label="Close"
              >
                <X size={18} />
              </button>
            </div>
            <div className={styles.modalContent}>
              {action.description && (
                <p className={styles.modalSummary}>{action.description}</p>
              )}

              <div className={styles.queriesListSection}>
                <h3 className={styles.queriesListTitle}>Top Affected Queries</h3>
                {loadingQueries && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#6B7280', fontSize: '12px' }}>
                    <Loader2 size={16} className={styles.spin} />
                    Loading queries…
                  </div>
                )}
                {queriesError && (
                  <p style={{ color: '#DC2626', fontSize: '12px' }}>{queriesError}</p>
                )}
                {!loadingQueries && !queriesError && affectedQueries?.queries?.length > 0 && (
                  <ul className={styles.queriesList}>
                    {affectedQueries.queries.map((q, i) => (
                      <li key={i} className={styles.queryListItem}>
                        <pre className={styles.queryPreview}>{q.queryText || '—'}</pre>
                        <div className={styles.queryMeta}>
                          {q.avgExecutionTimeMs != null && (
                            <span>Avg {Number(q.avgExecutionTimeMs).toFixed(0)} ms</span>
                          )}
                          {q.callCount != null && q.callCount > 0 && (
                            <span>{Number(q.callCount).toLocaleString()} calls</span>
                          )}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
                {!loadingQueries && !queriesError && (!affectedQueries?.queries?.length) && affectedQueries !== null && (
                  <div className={styles.emptyState}>
                    <p>Detailed query text is not available from the latest analysis snapshot.</p>
                    <p className={styles.emptyStateSub}>
                      This can happen if the queries were captured in a previous analysis window or aggregated from system statistics (e.g. pg_stat_statements) without full text retention.
                    </p>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
