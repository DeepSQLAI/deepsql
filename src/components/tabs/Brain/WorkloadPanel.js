'use client'

import { useState, useEffect, useCallback } from 'react'
import {
  Activity,
  AlertCircle,
  Loader,
  Play,
  RefreshCw,
  Zap,
  Database,
  Clock,
  TrendingUp,
  Server,
  Layers,
  CheckCircle,
  Info
} from 'lucide-react'
import { brainAPI } from '@/lib/api/client'
import { ActionGuard } from '@/components/ActionGuard'
import { HelpTooltip } from './components/HelpTooltip'
import { WORKLOAD_INTELLIGENCE } from './utils/helpText'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Workload Panel - Brain 2.0 ML Feature
 * Shows database workload characterization (OLTP/OLAP/HYBRID/BATCH)
 * with automatic learning progress display.
 *
 * Metrics collection and characterization run automatically in the background:
 * - Metrics collected every 15 minutes
 * - Workload characterized every 4 hours (when >=10 snapshots exist)
 */
export function WorkloadPanel({ connectionId }) {
  const [status, setStatus] = useState(null)
  const [profile, setProfile] = useState(null)
  const [similarWorkloads, setSimilarWorkloads] = useState([])
  const [loading, setLoading] = useState(false)
  const [characterizing, setCharacterizing] = useState(false)
  const [error, setError] = useState(null)

  const fetchData = useCallback(async () => {
    if (!connectionId) return

    setLoading(true)
    setError(null)
    try {
      // Fetch both status and profile
      const [statusData, profileData] = await Promise.allSettled([
        brainAPI.getWorkloadStatus(connectionId),
        brainAPI.getWorkloadProfile(connectionId)
      ])

      if (statusData.status === 'fulfilled') {
        setStatus(statusData.value)
      }

      if (profileData.status === 'fulfilled') {
        setProfile(profileData.value)
        // Fetch similar workloads if profile exists
        if (profileData.value?.workloadType) {
          const similar = await brainAPI.getSimilarWorkloads(connectionId, 3)
          setSimilarWorkloads(similar || [])
        }
      } else {
        setProfile(null)
      }
    } catch (err) {
      setError(err.message || 'Failed to load workload data')
    } finally {
      setLoading(false)
    }
  }, [connectionId])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const handleCharacterize = async () => {
    if (!connectionId) return

    setCharacterizing(true)
    setError(null)
    try {
      const result = await brainAPI.characterizeWorkload(connectionId)
      setProfile(result)

      // Refresh similar workloads and status
      if (result?.workloadType) {
        const [similar, newStatus] = await Promise.all([
          brainAPI.getSimilarWorkloads(connectionId, 3),
          brainAPI.getWorkloadStatus(connectionId)
        ])
        setSimilarWorkloads(similar || [])
        setStatus(newStatus)
      }
    } catch (err) {
      setError(err.message || 'Failed to characterize workload')
    } finally {
      setCharacterizing(false)
    }
  }

  const getWorkloadTypeConfig = (type) => {
    const configs = {
      OLTP: {
        color: 'var(--color-primary)',
        icon: <Zap size={16} />,
        description: 'Transaction-heavy workload optimized for fast writes and point queries'
      },
      OLAP: {
        color: 'var(--color-primary)',
        icon: <TrendingUp size={16} />,
        description: 'Analytics-heavy workload optimized for complex queries and aggregations'
      },
      MIXED: {
        color: 'var(--color-primary)',
        icon: <Layers size={16} />,
        description: 'Mixed workload with both transactional and analytical patterns'
      },
      HYBRID: {
        color: 'var(--color-primary)',
        icon: <Layers size={16} />,
        description: 'Mixed workload with both transactional and analytical patterns'
      },
      BATCH: {
        color: 'var(--color-primary)',
        icon: <Server size={16} />,
        description: 'Batch processing workload with scheduled bulk operations'
      },
      READ_HEAVY: {
        color: 'var(--color-primary)',
        icon: <Database size={16} />,
        description: 'Read-dominant workload with high query throughput'
      },
      WRITE_HEAVY: {
        color: 'var(--color-primary)',
        icon: <Activity size={16} />,
        description: 'Write-dominant workload with high insert/update rates'
      }
    }
    return configs[type] || { color: 'var(--color-primary)', icon: <Database size={16} />, description: 'Unknown workload type' }
  }

  const formatNumber = (num) => {
    if (!num && num !== 0) return '-'
    if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`
    if (num >= 1000) return `${(num / 1000).toFixed(1)}K`
    return typeof num === 'number' ? num.toFixed(1) : num
  }

  const formatLatency = (ms) => {
    if (!ms && ms !== 0) return '-'
    if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
    return `${ms.toFixed(0)}ms`
  }

  const formatTimeAgo = (dateStr) => {
    if (!dateStr) return 'Never'
    const date = new Date(dateStr)
    const now = new Date()
    const diffMs = now - date
    const diffMins = Math.floor(diffMs / 60000)
    const diffHours = Math.floor(diffMs / 3600000)
    const diffDays = Math.floor(diffMs / 86400000)

    if (diffMins < 1) return 'Just now'
    if (diffMins < 60) return `${diffMins}m ago`
    if (diffHours < 24) return `${diffHours}h ago`
    return `${diffDays}d ago`
  }

  const formatMetricValue = (metric, value) => {
    if (value === null || value === undefined) return '-'
    // Ratio metrics (0-1) shown as percentage
    if (metric.includes('ratio') || metric.includes('commit_ratio')) {
      return `${(value * 100).toFixed(1)}%`
    }
    // Count metrics
    if (metric.includes('connections') || metric.includes('queries')) {
      return value.toFixed(0)
    }
    // Default: show with 2 decimal places
    return value.toFixed(2)
  }

    const getMetricColor = (metric, value) => {
      // Cache/index ratios - green if high, red if low
      if (metric.includes('cache_hit') || metric.includes('index_usage') || metric.includes('commit_ratio')) {
      if (value >= 0.9) return 'var(--color-success)'
      if (value >= 0.7) return 'var(--color-warning)'
      return 'var(--color-danger)'
      }
      // Read/write ratio - neutral color
      if (metric.includes('read_write')) {
      return 'var(--color-dark-grey)'
      }
      // Slow queries - red if high
      if (metric.includes('slow')) {
      if (value > 100) return 'var(--color-danger)'
      if (value > 10) return 'var(--color-warning)'
      return 'var(--color-success)'
      }
    return 'var(--color-dark-grey)'
  }

  const snapshotCount = status?.snapshotCount || 0
  const minRequired = status?.minSnapshotsRequired || 10
  const readyToCharacterize = status?.readyToCharacterize || false
  const progressPercent = Math.min(100, (snapshotCount / minRequired) * 100)

  return (
    <div className={styles.brainPanel}>
      <div className={styles.brainHeader}>
        <div>
          <h3>Workload Intelligence</h3>
          <p>ML-powered workload characterization (learns automatically)</p>
        </div>
        <div className={styles.brainHeaderActions}>
          <HelpTooltip content={WORKLOAD_INTELLIGENCE.refreshButton}>
            <button
              className={styles.secondaryButton}
              onClick={fetchData}
              disabled={loading}
            >
              <RefreshCw size={14} className={loading ? styles.spinner : ''} />
            </button>
          </HelpTooltip>
          {readyToCharacterize && (
            <ActionGuard action="characterize-workload">
              <HelpTooltip content={WORKLOAD_INTELLIGENCE.characterizeButton}>
                <button
                  className={styles.profileButton}
                  onClick={handleCharacterize}
                  disabled={characterizing}
                >
                  {characterizing ? <Loader className={styles.spinner} size={14} /> : <Play size={14} />}
                  {characterizing ? 'Analyzing...' : 'Analyze Now'}
                </button>
              </HelpTooltip>
            </ActionGuard>
          )}
        </div>
      </div>

      {error && (
        <div className={styles.brainError}>
          <AlertCircle size={14} />
          <span>{error}</span>
        </div>
      )}

      {loading && !status && (
        <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
          <Loader className={styles.spinner} size={24} />
          <p style={{ marginTop: '12px' }}>Loading workload data...</p>
        </div>
      )}

      {!loading && status && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
          {/* Learning Progress Section */}
          <div style={{
            padding: '16px',
            background: 'var(--color-light-1)',
            borderRadius: '8px',
            border: '1px solid var(--color-light-2)'
          }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Activity size={16} style={{ color: 'var(--color-light-5)' }} />
                <span style={{ fontWeight: 500, fontSize: '14px' }}>Learning Progress</span>
              </div>
              <HelpTooltip content={{
                title: 'Automatic Learning',
                description: 'Workload metrics are collected every 15 minutes. Characterization runs every 4 hours once enough data is available.',
              }}>
                <div style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '4px',
                  fontSize: '12px',
                  color: 'var(--color-light-6)',
                  cursor: 'help'
                }}>
                  <Info size={12} />
                  Auto-learning enabled
                </div>
              </HelpTooltip>
            </div>

            {/* Progress Bar */}
            <div style={{ marginBottom: '12px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '6px' }}>
                <span style={{ fontSize: '13px', color: 'var(--color-light-6)' }}>
                  Metrics snapshots collected
                </span>
                <HelpTooltip content={{
                  title: 'Metrics Snapshots',
                  description: 'Periodic captures of database performance metrics (cache hit ratio, read/write ratio, transactions, etc.) collected every 15 minutes.',
                  impact: readyToCharacterize
                    ? `With ${snapshotCount} snapshots, you have plenty of data for accurate workload classification.`
                    : `Need ${minRequired} snapshots minimum before workload can be characterized.`,
                  recommendation: readyToCharacterize
                    ? 'Click "Analyze Now" to run workload characterization, or wait for automatic analysis every 4 hours.'
                    : 'Keep the connection active. Snapshots are collected automatically every 15 minutes.'
                }}>
                  <span style={{ fontSize: '13px', fontWeight: 500, cursor: 'help' }}>
                    {readyToCharacterize ? (
                      <span style={{ color: 'var(--color-success)' }}>
                        {snapshotCount} snapshots (ready)
                      </span>
                    ) : (
                      <>{snapshotCount} / {minRequired} required</>
                    )}
                  </span>
                </HelpTooltip>
              </div>
              {!readyToCharacterize && (
                <div style={{
                  height: '8px',
                  background: 'var(--color-light-2)',
                  borderRadius: '4px',
                  overflow: 'hidden'
                }}>
                  <div style={{
                    width: `${progressPercent}%`,
                    height: '100%',
                    background: 'var(--color-primary)',
                    borderRadius: '4px',
                    transition: 'width 0.3s ease'
                  }} />
                </div>
              )}
            </div>

            {/* Status Info */}
            <div style={{ display: 'flex', gap: '24px', flexWrap: 'wrap' }}>
              {status.lastSnapshotAt && (
                <div style={{ fontSize: '12px' }}>
                  <span style={{ color: 'var(--color-light-6)' }}>Last snapshot: </span>
                  <span style={{ fontWeight: 500 }}>{formatTimeAgo(status.lastSnapshotAt)}</span>
                  {status.lastSnapshotMetricsCount && (
                    <HelpTooltip content={WORKLOAD_INTELLIGENCE.collectedMetrics}>
                      <span style={{ color: 'var(--color-light-6)', cursor: 'help', borderBottom: '1px dotted var(--color-light-5)' }}> ({status.lastSnapshotMetricsCount} metrics)</span>
                    </HelpTooltip>
                  )}
                </div>
              )}
              {status.profileLastUpdated && (
                <div style={{ fontSize: '12px' }}>
                  <span style={{ color: 'var(--color-light-6)' }}>Profile updated: </span>
                  <span style={{ fontWeight: 500 }}>{formatTimeAgo(status.profileLastUpdated)}</span>
                </div>
              )}
            </div>

            {/* Ready Status */}
            {!readyToCharacterize && (
              <div style={{
                marginTop: '12px',
                padding: '8px 12px',
                background: 'var(--color-warning-soft)',
                borderRadius: '6px',
                fontSize: '12px',
                color: 'var(--color-warning)',
                display: 'flex',
                alignItems: 'center',
                gap: '8px'
              }}>
                <Clock size={14} />
                Need {minRequired - snapshotCount} more snapshots before characterization is available.
                Collecting automatically every 15 minutes.
              </div>
            )}
            {readyToCharacterize && !profile && (
              <div style={{
                marginTop: '12px',
                padding: '8px 12px',
                background: 'var(--color-success-soft)',
                borderRadius: '6px',
                fontSize: '12px',
                color: 'var(--color-success)',
                display: 'flex',
                alignItems: 'center',
                gap: '8px'
              }}>
                <CheckCircle size={14} />
                Ready to characterize! Click &quot;Analyze Now&quot; or wait for automatic analysis.
              </div>
            )}
          </div>

          {/* Workload Profile (if exists) */}
          {profile && (
            <>
              {/* Workload Type Badge */}
              <div style={{
                display: 'flex',
                alignItems: 'center',
                gap: '16px',
                padding: '16px',
                background: 'var(--color-light-1)',
                borderRadius: '8px',
                border: `2px solid ${getWorkloadTypeConfig(profile.workloadType).color}`
              }}>
                <div style={{
                  width: '48px',
                  height: '48px',
                  borderRadius: '50%',
                  background: getWorkloadTypeConfig(profile.workloadType).color,
                  color: 'white',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center'
                }}>
                  {getWorkloadTypeConfig(profile.workloadType).icon}
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <HelpTooltip content={WORKLOAD_INTELLIGENCE.workloadTypes[profile.workloadType]}>
                      <span style={{ fontWeight: 600, fontSize: '18px', cursor: 'help' }}>{profile.workloadType}</span>
                    </HelpTooltip>
                    {profile.workloadSubtype && (
                      <span style={{
                        fontSize: '12px',
                        padding: '2px 8px',
                        background: 'var(--color-light-2)',
                        borderRadius: '12px',
                        color: 'var(--color-light-6)'
                      }}>
                        {profile.workloadSubtype}
                      </span>
                    )}
                    {(profile.classificationConfidence || profile.confidence) && (
                      <HelpTooltip content={WORKLOAD_INTELLIGENCE.confidence}>
                        <span style={{
                          fontSize: '12px',
                          padding: '2px 8px',
                          background: 'var(--color-light-2)',
                          borderRadius: '12px',
                          color: 'var(--color-light-6)',
                          cursor: 'help'
                        }}>
                          {((profile.classificationConfidence || profile.confidence)).toFixed(0)}% confidence
                        </span>
                      </HelpTooltip>
                    )}
                  </div>
                  <p style={{ fontSize: '13px', color: 'var(--color-light-6)', marginTop: '4px' }}>
                    {getWorkloadTypeConfig(profile.workloadType).description}
                  </p>
                </div>
              </div>

              {/* Key Metrics Grid */}
              {(profile.throughputQps || profile.latencyP50Ms || profile.latencyP99Ms) && (
                <div style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
                  gap: '12px'
                }}>
                  {profile.throughputQps && (
                    <HelpTooltip content={WORKLOAD_INTELLIGENCE.metrics.queriesPerSecond}>
                      <MetricCard
                        icon={<Zap size={16} />}
                        label="Throughput"
                        value={`${formatNumber(profile.throughputQps)} QPS`}
                        color="var(--color-primary)"
                      />
                    </HelpTooltip>
                  )}
                  {profile.latencyP50Ms && (
                    <HelpTooltip content={WORKLOAD_INTELLIGENCE.metrics.avgLatency}>
                      <MetricCard
                        icon={<Clock size={16} />}
                        label="P50 Latency"
                        value={formatLatency(profile.latencyP50Ms)}
                        color="var(--color-warning)"
                      />
                    </HelpTooltip>
                  )}
                  {profile.latencyP99Ms && (
                    <HelpTooltip content={WORKLOAD_INTELLIGENCE.metrics.p95Latency}>
                      <MetricCard
                        icon={<TrendingUp size={16} />}
                        label="P99 Latency"
                        value={formatLatency(profile.latencyP99Ms)}
                        color="var(--color-primary)"
                      />
                    </HelpTooltip>
                  )}
                  {profile.performanceScore && (
                    <MetricCard
                      icon={<Activity size={16} />}
                      label="Performance Score"
                      value={`${profile.performanceScore.toFixed(0)}/100`}
                      color="var(--color-success)"
                    />
                  )}
                </div>
              )}

              {/* Key Metric Values (with actual numbers) */}
              {profile.keyMetricValues && Object.keys(profile.keyMetricValues).length > 0 && (
                <div>
                  <HelpTooltip content={WORKLOAD_INTELLIGENCE.workloadFeatures}>
                    <h4 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '12px', cursor: 'help', display: 'inline-block' }}>
                      Key Metrics
                    </h4>
                  </HelpTooltip>
                  <div style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                    gap: '8px'
                  }}>
                    {Object.entries(profile.keyMetricValues).map(([metric, value]) => {
                      const metricHelp = WORKLOAD_INTELLIGENCE.keyMetrics[metric] || {
                        title: metric.replace(/_/g, ' '),
                        description: `Current value of ${metric.replace(/_/g, ' ')} from database performance counters.`
                      }
                      return (
                        <HelpTooltip key={metric} content={metricHelp} block>
                          <div style={{
                            padding: '10px 14px',
                            background: 'var(--color-light-1)',
                            borderRadius: '6px',
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                            cursor: 'help',
                            width: '100%',
                            boxSizing: 'border-box'
                          }}>
                            <span style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>
                              {metric.replace(/_/g, ' ')}
                            </span>
                            <span style={{
                              fontSize: '14px',
                              fontWeight: 600,
                              fontFamily: 'monospace',
                              color: getMetricColor(metric, value),
                              marginLeft: '8px',
                              flexShrink: 0
                            }}>
                              {formatMetricValue(metric, value)}
                            </span>
                          </div>
                        </HelpTooltip>
                      )
                    })}
                  </div>
                </div>
              )}

              {/* Classification Reasoning */}
              {profile.classificationReasoning && (
                <div>
                  <h4 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '12px' }}>
                    Classification Reasoning
                  </h4>
                  <div style={{
                    padding: '16px',
                    background: 'var(--color-light-1)',
                    borderRadius: '8px',
                    fontSize: '13px',
                    lineHeight: '1.6',
                    whiteSpace: 'pre-wrap',
                    fontFamily: 'inherit'
                  }}>
                    {profile.classificationReasoning}
                  </div>
                </div>
              )}

              {/* Selected Metrics (fallback if no keyMetricValues) */}
              {(!profile.keyMetricValues || Object.keys(profile.keyMetricValues).length === 0) &&
               profile.selectedMetrics && profile.selectedMetrics.length > 0 && (
                <div>
                  <HelpTooltip content={WORKLOAD_INTELLIGENCE.workloadFeatures}>
                    <h4 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '12px', cursor: 'help', display: 'inline-block' }}>
                      Key Workload Indicators
                    </h4>
                  </HelpTooltip>
                  <div style={{
                    display: 'flex',
                    flexWrap: 'wrap',
                    gap: '8px'
                  }}>
                    {profile.selectedMetrics.map((metric, idx) => (
                      <div key={idx} style={{
                        padding: '6px 12px',
                        background: 'var(--color-light-1)',
                        borderRadius: '4px',
                        fontSize: '12px',
                        fontWeight: 500
                      }}>
                        {metric.replace(/_/g, ' ')}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Similar Workloads */}
              {similarWorkloads.length > 0 && (
                <div>
                  <HelpTooltip content={WORKLOAD_INTELLIGENCE.similarWorkloads}>
                    <h4 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '12px', cursor: 'help', display: 'inline-block' }}>
                      Similar Workloads
                    </h4>
                  </HelpTooltip>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {similarWorkloads.map((similar, idx) => (
                      <div key={idx} style={{
                        padding: '12px',
                        background: 'var(--color-light-1)',
                        borderRadius: '6px',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between'
                      }}>
                        <div>
                          <span style={{ fontWeight: 500 }}>{similar.name || similar.connectionId || `Workload ${idx + 1}`}</span>
                          {similar.workloadType && (
                            <span style={{
                              marginLeft: '8px',
                              fontSize: '12px',
                              padding: '2px 6px',
                              background: getWorkloadTypeConfig(similar.workloadType).color,
                              color: 'white',
                              borderRadius: '4px'
                            }}>
                              {similar.workloadType}
                            </span>
                          )}
                        </div>
                        {similar.similarity !== undefined && (
                          <span style={{ fontSize: '13px', color: 'var(--color-light-6)' }}>
                            {(similar.similarity * 100).toFixed(0)}% similar
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}

function MetricCard({ icon, label, value, color }) {
  return (
    <div style={{
      padding: '12px',
      background: 'var(--color-light-1)',
      borderRadius: '6px',
      borderLeft: `3px solid ${color}`
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px' }}>
        <span style={{ color }}>{icon}</span>
        <span style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>{label}</span>
      </div>
      <div style={{ fontSize: '18px', fontWeight: 600 }}>{value}</div>
    </div>
  )
}

export default WorkloadPanel
