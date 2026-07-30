'use client'

import { useState, useEffect } from 'react'
import { Loader, CheckCircle } from 'lucide-react'
import { brainAPI } from '@/lib/api/client'

/**
 * ML Health Widget - Brain 2.0 Feature
 *
 * Shows the readiness status of Brain 2.0 ML features:
 * 1. Workload Classified - Has the workload been characterized
 * 2. Knobs Ranked - Have configuration parameters been ranked by impact
 * 3. Statistics Available - Have column statistics been collected
 * 4. Patterns Learned - Have query plan patterns been learned
 */
export function MLHealthWidget({ connectionId, onNavigate }) {
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    async function fetchStatus() {
      if (!connectionId) {
        setLoading(false)
        return
      }
      try {
        const data = await brainAPI.getMLOverview(connectionId)
        setStatus(data)
      } catch (err) {
        console.error('Failed to fetch ML status:', err)
      } finally {
        setLoading(false)
      }
    }
    fetchStatus()
  }, [connectionId])

  if (loading) {
    return (
      <div style={{
        padding: '16px',
        background: 'var(--color-light-1)',
        borderRadius: '8px',
        marginBottom: '16px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center'
      }}>
        <Loader size={16} style={{ animation: 'spin 1s linear infinite' }} />
        <span style={{ marginLeft: '8px', fontSize: '13px', color: 'var(--color-light-6)' }}>
          Loading ML status...
        </span>
      </div>
    )
  }

  if (!status) return null

  // Response shape: { workloadProfile: {...}, configTuning: {...}, queryIntelligence: {...} }
  const steps = [
    {
      key: 'workload',
      label: 'Workload Classified',
      done: !!status.workloadProfile?.type,
      detail: status.workloadProfile?.type
        ? `${status.workloadProfile.type} (${status.workloadProfile.confidence?.toFixed(0)}%)`
        : null,
      action: 'Collect workload metrics',
      tab: 'workload'
    },
    {
      key: 'knobs',
      label: 'Knobs Ranked',
      done: status.configTuning?.knobsRanked > 0,
      detail: status.configTuning?.knobsRanked
        ? `${status.configTuning.knobsRanked} knobs ranked`
        : null,
      action: 'Identify important knobs',
      tab: 'config'
    },
    {
      key: 'stats',
      label: 'Statistics Available',
      done: status.queryIntelligence?.statisticsCount > 0,
      detail: status.queryIntelligence?.statisticsCount
        ? `${status.queryIntelligence.statisticsCount} columns analyzed`
        : null,
      action: 'Analyze key columns',
      tab: 'overview'
    },
    {
      key: 'patterns',
      label: 'Patterns Learned',
      done: status.queryIntelligence?.patternStats?.total > 0,
      detail: status.queryIntelligence?.patternStats?.total
        ? `${status.queryIntelligence.patternStats.total} patterns (${status.queryIntelligence.patternStats.reliable || 0} reliable)`
        : null,
      action: 'Ingest slow queries',
      tab: 'query-intelligence'
    }
  ]

  const completedCount = steps.filter(s => s.done).length
  const readinessPercent = (completedCount / steps.length) * 100

  const handleAction = (tab) => {
    if (onNavigate) {
      onNavigate(tab)
    }
  }

  return (
    <div style={{
      padding: '16px',
      background: 'var(--color-light-1)',
      borderRadius: '8px',
      marginBottom: '16px'
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
        <h4 style={{ margin: 0, fontSize: '14px', fontWeight: 600 }}>
          Brain 2.0 Learning Status
        </h4>
        <span style={{
          padding: '4px 8px',
          borderRadius: '4px',
          fontSize: '12px',
          fontWeight: 500,
          background: readinessPercent === 100 ? 'var(--color-success-soft)' : 'var(--color-warning-soft)',
          color: readinessPercent === 100 ? 'var(--color-success)' : 'var(--color-warning)'
        }}>
          {readinessPercent.toFixed(0)}% Ready
        </span>
      </div>

      {/* Progress bar */}
      <div style={{
        height: '6px',
        background: 'var(--color-light-3)',
        borderRadius: '3px',
        marginBottom: '16px',
        overflow: 'hidden'
      }}>
        <div style={{
          width: `${readinessPercent}%`,
          height: '100%',
          background: readinessPercent === 100 ? 'var(--color-success)' : 'var(--color-primary)',
          borderRadius: '3px',
          transition: 'width 0.3s ease'
        }} />
      </div>

      {/* Steps */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {steps.map((step, idx) => (
          <div key={step.key} style={{
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            padding: '8px',
            background: step.done ? 'var(--color-success-soft)' : 'white',
            borderRadius: '6px',
            border: '1px solid var(--color-light-2)'
          }}>
            {step.done ? (
              <CheckCircle size={16} style={{ color: 'var(--color-success)', flexShrink: 0 }} />
            ) : (
              <div style={{
                width: '16px',
                height: '16px',
                borderRadius: '50%',
                border: '2px solid var(--color-light-4)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '10px',
                fontWeight: 600,
                color: 'var(--color-light-6)',
                flexShrink: 0
              }}>
                {idx + 1}
              </div>
            )}
            <div style={{ flex: 1, minWidth: 0 }}>
              <span style={{
                fontSize: '13px',
                fontWeight: 500,
                color: step.done ? 'var(--color-success)' : 'var(--color-dark-1)'
              }}>
                {step.label}
              </span>
              {step.done && step.detail && (
                <div style={{ fontSize: '11px', color: 'var(--color-light-6)', marginTop: '2px' }}>
                  {step.detail}
                </div>
              )}
            </div>
            {!step.done && (
              <button
                onClick={() => handleAction(step.tab)}
                style={{
                  padding: '4px 8px',
                  background: 'var(--color-primary)',
                  color: 'white',
                  border: 'none',
                  borderRadius: '4px',
                  fontSize: '11px',
                  cursor: 'pointer',
                  whiteSpace: 'nowrap'
                }}
              >
                {step.action}
              </button>
            )}
          </div>
        ))}
      </div>

      {/* Workload Profile Details */}
      {status.workloadProfile?.type && (
        <div style={{
          marginTop: '12px',
          padding: '12px',
          background: 'white',
          borderRadius: '6px',
          fontSize: '12px',
          border: '1px solid var(--color-light-2)'
        }}>
          <div style={{ color: 'var(--color-light-6)', marginBottom: '6px' }}>Current Workload Profile</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
            <div style={{ fontWeight: 600, fontSize: '14px' }}>
              {status.workloadProfile.type}
            </div>
            {status.workloadProfile.subtype && (
              <span style={{
                padding: '2px 8px',
                background: 'var(--color-light-2)',
                borderRadius: '4px',
                fontSize: '11px'
              }}>
                {status.workloadProfile.subtype}
              </span>
            )}
            <span style={{
              marginLeft: 'auto',
              color: status.workloadProfile.confidence >= 80 ? 'var(--color-success)' :
                     status.workloadProfile.confidence >= 60 ? 'var(--color-warning)' : 'var(--color-danger)',
              fontWeight: 500
            }}>
              {status.workloadProfile.confidence?.toFixed(0)}% confidence
            </span>
          </div>
          {status.workloadProfile.lastUpdated && (
            <div style={{ fontSize: '10px', color: 'var(--color-light-6)', marginTop: '6px' }}>
              Last updated: {new Date(status.workloadProfile.lastUpdated).toLocaleString()}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default MLHealthWidget
