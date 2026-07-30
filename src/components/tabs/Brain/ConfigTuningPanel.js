'use client'

import { useState, useEffect, useCallback } from 'react'
import {
  AlertCircle,
  Loader,
  RefreshCw,
  Settings,
  Play,
  Sliders,
  TrendingUp,
  Target,
  Beaker,
  CheckCircle,
  Clock,
  AlertTriangle,
  ChevronDown,
  ChevronUp,
  Zap,
  Info
} from 'lucide-react'
import { brainAPI, connectionAPI } from '@/lib/api/client'
import { ActionGuard } from '@/components/ActionGuard'
import { HelpTooltip } from './components/HelpTooltip'
import { CONFIG_TUNING } from './utils/helpText'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Config Tuning Panel - Brain 2.0 ML Feature
 * Shows knob rankings, ML-powered tuning recommendations, and experiments
 */
export function ConfigTuningPanel({ connectionId }) {
  const [activeTab, setActiveTab] = useState('knobs')
  const [knobs, setKnobs] = useState([])
  const [recommendations, setRecommendations] = useState([])
  const [experiments, setExperiments] = useState([])
  const [loading, setLoading] = useState(false)
  const [identifying, setIdentifying] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [autoRunning, setAutoRunning] = useState(false)
  const [completingExperiment, setCompletingExperiment] = useState(null)
  const [error, setError] = useState(null)
  const [expandedKnob, setExpandedKnob] = useState(null)
  const [missingInstanceSpecs, setMissingInstanceSpecs] = useState(false)
  const [connectionName, setConnectionName] = useState('')

  const fetchData = useCallback(async () => {
    if (!connectionId) return

    setLoading(true)
    setError(null)
    try {
      const [knobsData, experimentsData] = await Promise.allSettled([
        brainAPI.getTopKnobs(connectionId, 20),
        brainAPI.getTuningExperiments(connectionId)
      ])

      if (knobsData.status === 'fulfilled') setKnobs(knobsData.value || [])
      if (experimentsData.status === 'fulfilled') setExperiments(experimentsData.value || [])
    } catch (err) {
      setError(err.message || 'Failed to load config tuning data')
    } finally {
      setLoading(false)
    }
  }, [connectionId])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  // Check if instance specs are configured for better recommendations
  useEffect(() => {
    const checkInstanceSpecs = async () => {
      if (!connectionId) return
      try {
        const connections = await connectionAPI.getAllConnections()
        const conn = connections.find(c => c.id === connectionId)
        if (conn) {
          setConnectionName(conn.connectionName || '')
          const hasSpecs = conn.instanceClass || conn.instanceVcpus || conn.instanceMemoryGb
          setMissingInstanceSpecs(!hasSpecs)
        }
      } catch (err) {
        // Silently fail - this is a non-critical hint
      }
    }
    checkInstanceSpecs()
  }, [connectionId])

  const handleIdentifyKnobs = async () => {
    if (!connectionId) return

    setIdentifying(true)
    setError(null)
    try {
      const result = await brainAPI.identifyKnobs(connectionId)
      setKnobs(result || [])
    } catch (err) {
      setError(err.message || 'Failed to identify knobs')
    } finally {
      setIdentifying(false)
    }
  }

  const handleGenerateRecommendations = async () => {
    if (!connectionId) return

    setGenerating(true)
    setError(null)
    try {
      const result = await brainAPI.getConfigRecommendations(connectionId)
      setRecommendations(result || [])
      setActiveTab('recommendations')
    } catch (err) {
      setError(err.message || 'Failed to generate recommendations')
    } finally {
      setGenerating(false)
    }
  }

  // Auto-run both steps: Identify Knobs → Get Recommendations
  const handleAutoAnalyze = async () => {
    if (!connectionId) return

    setAutoRunning(true)
    setError(null)
    try {
      // Step 1: Identify Knobs
      const knobsResult = await brainAPI.identifyKnobs(connectionId)
      setKnobs(knobsResult || [])

      // Step 2: Generate Recommendations
      const recsResult = await brainAPI.getConfigRecommendations(connectionId)
      setRecommendations(recsResult || [])
      setActiveTab('recommendations')
    } catch (err) {
      setError(err.message || 'Failed to complete analysis')
    } finally {
      setAutoRunning(false)
    }
  }

  const handleCreateExperiment = async (knobName, currentValue, recommendedValue, recommendationId = null) => {
    if (!connectionId) return

    try {
      // Construct knobChanges map in the format expected by backend
      const knobChanges = {
        [knobName]: {
          current: String(currentValue ?? ''),
          recommended: String(recommendedValue ?? '')
        }
      }
      const experiment = await brainAPI.createTuningExperiment(connectionId, knobChanges, recommendationId)
      setExperiments(prev => [experiment, ...prev])
      setActiveTab('experiments')
    } catch (err) {
      setError(err.message || 'Failed to create experiment')
    }
  }

  // Complete a running experiment - collects final metrics and calculates improvement
  const handleCompleteExperiment = async (experimentId) => {
    setCompletingExperiment(experimentId)
    setError(null)

    try {
      const result = await brainAPI.completeExperiment(experimentId, connectionId)

      // Show success feedback
      if (result.overallImprovementPercent !== undefined) {
        const improvement = result.overallImprovementPercent
        const message = improvement > 0
          ? `Experiment completed! ${improvement.toFixed(1)}% improvement detected.`
          : improvement < 0
            ? `Experiment completed. Performance decreased by ${Math.abs(improvement).toFixed(1)}%. Consider rolling back.`
            : 'Experiment completed. No significant change detected.'
        console.log(message)
      }

      // Refresh experiments list
      await fetchData()
    } catch (err) {
      setError(`Failed to complete experiment: ${err.message || 'Unknown error'}`)
    } finally {
      setCompletingExperiment(null)
    }
  }

  // Cancel a running experiment
  const handleCancelExperiment = async (experimentId) => {
    if (!window.confirm('Cancel this experiment? Baseline metrics will be discarded.')) {
      return
    }

    try {
      await brainAPI.cancelExperiment(experimentId, connectionId)
      await fetchData()
    } catch (err) {
      setError(`Failed to cancel experiment: ${err.message || 'Unknown error'}`)
    }
  }

  // Format elapsed time since experiment started
  const formatElapsedTime = (startDate) => {
    if (!startDate) return ''
    const now = new Date()
    const start = new Date(startDate)
    const diffMs = now - start
    const diffMins = Math.floor(diffMs / 60000)
    const diffHours = Math.floor(diffMins / 60)
    const diffDays = Math.floor(diffHours / 24)

    if (diffDays > 0) return `${diffDays}d ${diffHours % 24}h`
    if (diffHours > 0) return `${diffHours}h ${diffMins % 60}m`
    return `${diffMins}m`
  }

  // Extract knob info from experiment's knobChanges map
  const getExperimentKnobInfo = (exp) => {
    const knobChanges = exp.knobChanges || {}
    const entries = Object.entries(knobChanges)
    if (entries.length === 0) {
      return { knobName: 'Unknown', originalValue: '-', targetValue: '-', extraCount: 0 }
    }
    const [knobName, values] = entries[0]
    return {
      knobName,
      originalValue: values?.current || values?.old || '-',
      targetValue: values?.recommended || values?.new || '-',
      extraCount: entries.length - 1
    }
  }

  const getImpactColor = (impact) => {
    if (impact >= 0.8) return 'var(--color-danger)'
    return 'var(--color-dark-grey)'
  }

  const getImpactLabel = (impact) => {
    if (impact >= 0.8) return 'Critical'
    if (impact >= 0.5) return 'High'
    if (impact >= 0.3) return 'Medium'
    return 'Low'
  }

  const getImpactHelpContent = (impact) => {
    if (impact >= 0.8) return CONFIG_TUNING.impactLevels.CRITICAL
    if (impact >= 0.5) return CONFIG_TUNING.impactLevels.HIGH
    if (impact >= 0.3) return CONFIG_TUNING.impactLevels.MEDIUM
    return CONFIG_TUNING.impactLevels.LOW
  }

  const formatValue = (value) => {
    if (value === null || value === undefined) return '-'
    if (typeof value === 'boolean') return value ? 'on' : 'off'
    if (typeof value === 'number') {
      if (value >= 1073741824) return `${(value / 1073741824).toFixed(1)}GB`
      if (value >= 1048576) return `${(value / 1048576).toFixed(1)}MB`
      if (value >= 1024) return `${(value / 1024).toFixed(1)}KB`
      return value.toString()
    }
    return String(value)
  }

  const tabs = [
    {
      id: 'knobs',
      label: 'Knob Rankings',
      icon: <Sliders size={14} />,
      helpContent: CONFIG_TUNING.knobsTab
    },
    {
      id: 'recommendations',
      label: 'ML Recommendations',
      icon: <Target size={14} />,
      helpContent: CONFIG_TUNING.recommendationsTab
    },
    {
      id: 'experiments',
      label: 'Experiments',
      icon: <Beaker size={14} />,
      helpContent: CONFIG_TUNING.experimentsTab
    }
  ]

  return (
    <div className={styles.brainPanel}>
      <div className={styles.brainHeader}>
        <div>
          <h3>Config Tuning</h3>
          <p>ML-powered configuration knob analysis and tuning recommendations</p>
        </div>
        <div className={styles.brainHeaderActions}>
          <HelpTooltip content={CONFIG_TUNING.refreshButton}>
            <button
              className={styles.secondaryButton}
              onClick={fetchData}
              disabled={loading || autoRunning}
            >
              <RefreshCw size={14} className={loading ? styles.spinner : ''} />
            </button>
          </HelpTooltip>
          <ActionGuard action="identify-knobs">
            <HelpTooltip content={{
              title: 'Auto Analyze',
              description: 'Automatically runs both steps: identifies important knobs, then generates ML-powered recommendations.',
              recommendation: 'Best for first-time setup or periodic re-analysis.'
            }}>
              <button
                className={styles.profileButton}
                onClick={handleAutoAnalyze}
                disabled={identifying || generating || autoRunning}
              >
                {autoRunning ? <Loader className={styles.spinner} size={14} /> : <Zap size={14} />}
                {autoRunning ? 'Analyzing...' : 'Auto Analyze'}
              </button>
            </HelpTooltip>
          </ActionGuard>
        </div>
      </div>

      {/* Info Banner for first-time users */}
      {knobs.length === 0 && recommendations.length === 0 && !loading && !autoRunning && (
        <div style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: '12px',
          padding: '12px 16px',
          background: '#f0f9ff',
          border: '1px solid #bae6fd',
          borderRadius: '8px',
          marginBottom: '16px'
        }}>
          <Info size={18} style={{ color: '#0284c7', flexShrink: 0, marginTop: '2px' }} />
          <div style={{ fontSize: '13px', color: '#0369a1' }}>
            <strong>Getting Started:</strong> Click <strong>Auto Analyze</strong> to automatically identify important configuration knobs and generate ML-powered recommendations.
            Or follow the steps manually: first identify knobs to see which parameters have the most impact, then get recommendations tailored to your workload.
          </div>
        </div>
      )}

      {/* Suggestion Banner for missing instance specs */}
      {missingInstanceSpecs && !loading && !autoRunning && (
        <div style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: '12px',
          padding: '12px 16px',
          background: '#fefce8',
          border: '1px solid #fef08a',
          borderRadius: '8px',
          marginBottom: '16px'
        }}>
          <AlertTriangle size={18} style={{ color: '#ca8a04', flexShrink: 0, marginTop: '2px' }} />
          <div style={{ fontSize: '13px', color: '#854d0e' }}>
            <strong>Better recommendations available:</strong> Add instance specs (vCPUs, RAM, storage type) to your connection
            {connectionName && <> &ldquo;{connectionName}&rdquo;</>} for hardware-aware tuning recommendations.
            Edit your connection via <strong>Manage Connections</strong> and fill in the <strong>Instance Specs</strong> section.
            This helps size parameters like buffer pools based on your actual RAM.
          </div>
        </div>
      )}

      {error && (
        <div className={styles.brainError}>
          <AlertCircle size={14} />
          <span>{error}</span>
        </div>
      )}

      {/* Tab Navigation */}
      <div style={{
        display: 'flex',
        gap: '4px',
        padding: '4px',
        background: 'var(--color-light-1)',
        borderRadius: '8px',
        marginBottom: '16px'
      }}>
        {tabs.map((tab) => (
          <HelpTooltip key={tab.id} content={tab.helpContent}>
            <button
              onClick={() => setActiveTab(tab.id)}
              style={{
                flex: 1,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '6px',
                padding: '8px 12px',
                border: 'none',
                borderRadius: '6px',
                background: activeTab === tab.id ? 'white' : 'transparent',
                color: activeTab === tab.id ? 'var(--color-dark-1)' : 'var(--color-light-6)',
                fontWeight: activeTab === tab.id ? 500 : 400,
                fontSize: '13px',
                cursor: 'pointer',
                transition: 'all 0.2s ease',
                boxShadow: activeTab === tab.id ? '0 1px 3px rgba(0,0,0,0.1)' : 'none'
              }}
            >
              {tab.icon}
              {tab.label}
            </button>
          </HelpTooltip>
        ))}
      </div>

      {(loading || autoRunning) && (
        <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
          <Loader className={styles.spinner} size={24} />
          <p style={{ marginTop: '12px', fontWeight: 500 }}>
            {autoRunning ? 'Running Auto Analysis...' : 'Loading config tuning data...'}
          </p>
          {autoRunning && (
            <p style={{ fontSize: '13px', marginTop: '8px', maxWidth: '300px', margin: '8px auto 0' }}>
              Identifying high-impact knobs and generating ML-powered recommendations
            </p>
          )}
        </div>
      )}

      {/* Knob Rankings Tab */}
      {!loading && !autoRunning && activeTab === 'knobs' && (
        <div>
          {knobs.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
              <Sliders size={32} style={{ marginBottom: '12px', opacity: 0.5 }} />
              <p style={{ fontWeight: 500, marginBottom: '8px' }}>Step 1: Identify High-Impact Knobs</p>
              <p style={{ fontSize: '13px', maxWidth: '400px', margin: '0 auto 16px' }}>
                Analyze your database configuration to identify which parameters have the most impact on performance.
                This uses ML techniques inspired by OtterTune to rank knobs by their effect on throughput and latency.
              </p>
              <ActionGuard action="identify-knobs">
                <button
                  onClick={handleIdentifyKnobs}
                  disabled={identifying}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '6px',
                    padding: '10px 20px',
                    background: 'var(--color-dark-grey)',
                    color: 'white',
                    border: 'none',
                    borderRadius: '6px',
                    fontSize: '13px',
                    fontWeight: 500,
                    cursor: 'pointer'
                  }}
                >
                  {identifying ? <Loader className={styles.spinner} size={14} /> : <Settings size={14} />}
                  Identify Knobs
                </button>
              </ActionGuard>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {knobs.map((knob, idx) => (
                <div key={idx} style={{
                  background: 'var(--color-light-1)',
                  borderRadius: '8px',
                  border: '1px solid var(--color-light-2)',
                  overflow: 'hidden'
                }}>
                  <div
                    style={{
                      padding: '12px 16px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      cursor: 'pointer'
                    }}
                    onClick={() => setExpandedKnob(expandedKnob === idx ? null : idx)}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                      <div style={{
                        width: '28px',
                        height: '28px',
                        borderRadius: '50%',
                        background: getImpactColor(knob.impactScore),
                        color: 'white',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '12px',
                        fontWeight: 600
                      }}>
                        {idx + 1}
                      </div>
                      <div>
                        <div style={{ fontWeight: 600, fontFamily: 'monospace' }}>{knob.knobName}</div>
                        <div style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>
                          Current: {formatValue(knob.currentValue)}
                        </div>
                      </div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                      <HelpTooltip content={getImpactHelpContent(knob.impactScore)}>
                        <span style={{
                          padding: '4px 8px',
                          borderRadius: '4px',
                          fontSize: '11px',
                          fontWeight: 500,
                          background: `${getImpactColor(knob.impactScore)}20`,
                          color: getImpactColor(knob.impactScore)
                        }}>
                          {getImpactLabel(knob.impactScore)} Impact
                        </span>
                      </HelpTooltip>
                      {expandedKnob === idx ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                    </div>
                  </div>

                  {expandedKnob === idx && (
                    <div style={{
                      padding: '12px 16px',
                      borderTop: '1px solid var(--color-light-2)',
                      background: 'white'
                    }}>
                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginBottom: '12px' }}>
                        <div>
                          <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '4px' }}>Category</div>
                          <div style={{ fontWeight: 500 }}>{knob.category || 'General'}</div>
                        </div>
                        <div>
                          <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '4px' }}>Impact Score</div>
                          <div style={{ fontWeight: 500 }}>{(knob.impactScore * 100).toFixed(0)}%</div>
                        </div>
                        {knob.recommendedValue !== undefined && (
                          <div>
                            <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '4px' }}>Recommended</div>
                            <div style={{ fontWeight: 500, color: 'var(--color-dark-grey)' }}>{formatValue(knob.recommendedValue)}</div>
                          </div>
                        )}
                        {knob.defaultValue !== undefined && (
                          <div>
                            <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '4px' }}>Default</div>
                            <div style={{ fontWeight: 500, color: 'var(--color-light-6)' }}>{formatValue(knob.defaultValue)}</div>
                          </div>
                        )}
                      </div>
                      {knob.description && (
                        <div style={{ fontSize: '13px', color: 'var(--color-light-6)', marginBottom: '12px' }}>
                          {knob.description}
                        </div>
                      )}
                      {/* Note: Knob Rankings show impact scores only, not recommendations.
                          To create experiments, use the "ML Recommendations" tab which provides
                          actual recommended values based on workload analysis. */}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ML Recommendations Tab */}
      {!loading && !autoRunning && activeTab === 'recommendations' && (
        <div>
          {recommendations.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
              <Target size={32} style={{ marginBottom: '12px', opacity: 0.5 }} />
              <p style={{ fontWeight: 500, marginBottom: '8px' }}>Step 2: Get ML-Powered Recommendations</p>
              <p style={{ fontSize: '13px', maxWidth: '400px', margin: '0 auto 16px' }}>
                {knobs.length === 0
                  ? 'First, identify knobs to understand which parameters matter most. Then get tailored recommendations based on your workload patterns.'
                  : 'Generate specific value recommendations for your high-impact knobs. The ML model analyzes your workload patterns to suggest optimal settings.'}
              </p>
              <ActionGuard action="generate-recommendations">
                <button
                  onClick={knobs.length === 0 ? handleAutoAnalyze : handleGenerateRecommendations}
                  disabled={generating || autoRunning}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '6px',
                    padding: '10px 20px',
                    background: 'var(--color-dark-grey)',
                    color: 'white',
                    border: 'none',
                    borderRadius: '6px',
                    fontSize: '13px',
                    fontWeight: 500,
                    cursor: 'pointer'
                  }}
                >
                  {generating ? <Loader className={styles.spinner} size={14} /> : knobs.length === 0 ? <Zap size={14} /> : <Play size={14} />}
                  {knobs.length === 0 ? 'Auto Analyze (Both Steps)' : 'Get Recommendations'}
                </button>
              </ActionGuard>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {recommendations.map((rec, idx) => (
                <div key={idx} style={{
                  padding: '16px',
                  background: 'var(--color-light-1)',
                  borderRadius: '8px',
                  border: '1px solid var(--color-light-2)'
                }}>
                  <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '12px' }}>
                    <div>
                      <div style={{ fontWeight: 600, fontFamily: 'monospace', marginBottom: '4px' }}>
                        {rec.parameterName}
                      </div>
                      <div style={{ fontSize: '13px', color: 'var(--color-light-6)' }}>
                        {rec.reasoning || 'ML-optimized recommendation based on workload analysis'}
                      </div>
                    </div>
                    {rec.estimatedImprovementPercent != null && (
                      <span style={{
                        padding: '4px 8px',
                        borderRadius: '4px',
                        fontSize: '11px',
                        fontWeight: 500,
                        background: 'var(--color-light-2)',
                        color: 'var(--color-dark-grey)',
                        whiteSpace: 'nowrap'
                      }}>
                        <TrendingUp size={12} style={{ display: 'inline', marginRight: '4px' }} />
                        +{rec.estimatedImprovementPercent.toFixed(0)}% improvement
                      </span>
                    )}
                  </div>

                  <div style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '16px',
                    padding: '12px',
                    background: 'white',
                    borderRadius: '6px'
                  }}>
                    <div style={{ flex: 1 }}>
                      <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '4px' }}>Current Value</div>
                      <div style={{ fontFamily: 'monospace', fontWeight: 500 }}>{formatValue(rec.currentValue)}</div>
                    </div>
                    <div style={{ color: 'var(--color-light-5)' }}>&rarr;</div>
                    <div style={{ flex: 1 }}>
                      <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '4px' }}>Recommended</div>
                      <div style={{ fontFamily: 'monospace', fontWeight: 500, color: 'var(--color-dark-grey)' }}>{formatValue(rec.recommendedValue)}</div>
                    </div>
                    <ActionGuard action="create-experiment">
                      <HelpTooltip content={CONFIG_TUNING.testButton}>
                        <button
                          onClick={() => handleCreateExperiment(rec.parameterName, rec.currentValue, rec.recommendedValue, rec.id)}
                          style={{
                            padding: '8px 12px',
                            background: 'var(--color-dark-grey)',
                            color: 'white',
                            border: 'none',
                            borderRadius: '6px',
                            fontSize: '12px',
                            cursor: 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px'
                          }}
                        >
                          <Beaker size={12} />
                          Test
                        </button>
                      </HelpTooltip>
                    </ActionGuard>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Experiments Tab */}
      {!loading && !autoRunning && activeTab === 'experiments' && (
        <div>
          {experiments.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '32px', color: 'var(--color-light-6)' }}>
              <Beaker size={32} style={{ marginBottom: '12px', opacity: 0.5 }} />
              <p style={{ fontWeight: 500, marginBottom: '8px' }}>Step 3: Test Configuration Changes</p>
              <p style={{ fontSize: '13px', maxWidth: '400px', margin: '0 auto 16px' }}>
                {recommendations.length === 0
                  ? 'Complete steps 1 and 2 first to get recommendations. Then you can create experiments to safely test changes before applying them to production.'
                  : 'Click "Test" on any recommendation to create an experiment. This helps you validate the improvement before committing to the change.'}
              </p>
              {recommendations.length === 0 && (
                <ActionGuard action="identify-knobs">
                  <button
                    onClick={handleAutoAnalyze}
                    disabled={autoRunning}
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: '6px',
                      padding: '10px 20px',
                      background: 'var(--color-dark-grey)',
                      color: 'white',
                      border: 'none',
                      borderRadius: '6px',
                      fontSize: '13px',
                      fontWeight: 500,
                      cursor: 'pointer'
                    }}
                  >
                    <Zap size={14} />
                    Start with Auto Analyze
                  </button>
                </ActionGuard>
              )}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {experiments.map((exp, idx) => {
                // Extract knob info from knobChanges map (Phase 1.2 fix)
                const { knobName, originalValue, targetValue, extraCount } = getExperimentKnobInfo(exp)

                return (
                  <div key={exp.id || idx} style={{
                    padding: '16px',
                    background: 'var(--color-light-1)',
                    borderRadius: '8px',
                    border: '1px solid var(--color-light-2)'
                  }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <ExperimentStatusIcon status={exp.status} />
                        <span style={{ fontWeight: 600, fontFamily: 'monospace' }}>{knobName}</span>
                        {extraCount > 0 && (
                          <span style={{ fontSize: '11px', color: 'var(--color-light-6)' }}>
                            +{extraCount} more
                          </span>
                        )}
                      </div>
                      <span style={{
                        padding: '4px 8px',
                        borderRadius: '4px',
                        fontSize: '11px',
                        fontWeight: 500,
                        background: getStatusColor(exp.status).bg,
                        color: getStatusColor(exp.status).text
                      }}>
                        {exp.status || 'PENDING'}
                      </span>
                    </div>

                    <div style={{
                      display: 'grid',
                      gridTemplateColumns: exp.status === 'COMPLETED' ? 'repeat(4, 1fr)' : 'repeat(2, 1fr)',
                      gap: '12px',
                      fontSize: '13px'
                    }}>
                      <div>
                        <div style={{ color: 'var(--color-light-6)', marginBottom: '2px' }}>Original</div>
                        <div style={{ fontFamily: 'monospace' }}>{formatValue(originalValue)}</div>
                      </div>
                      <div>
                        <div style={{ color: 'var(--color-light-6)', marginBottom: '2px' }}>Target</div>
                        <div style={{ fontFamily: 'monospace', color: 'var(--color-dark-grey)' }}>{formatValue(targetValue)}</div>
                      </div>
                      {exp.status === 'COMPLETED' && (
                        <>
                          <div>
                            <div style={{ color: 'var(--color-light-6)', marginBottom: '2px' }}>Latency Change</div>
                            <div style={{
                              fontWeight: 500,
                              color: exp.latencyImprovementPercent > 0 ? '#059669' : exp.latencyImprovementPercent < 0 ? 'var(--color-danger)' : 'var(--color-light-6)'
                            }}>
                              {exp.latencyImprovementPercent != null
                                ? `${exp.latencyImprovementPercent > 0 ? '-' : '+'}${Math.abs(exp.latencyImprovementPercent).toFixed(1)}%`
                                : '-'}
                            </div>
                          </div>
                          <div>
                            <div style={{ color: 'var(--color-light-6)', marginBottom: '2px' }}>Overall</div>
                            <div style={{
                              fontWeight: 500,
                              color: exp.overallImprovementPercent > 0 ? '#059669' : exp.overallImprovementPercent < 0 ? 'var(--color-danger)' : 'var(--color-light-6)'
                            }}>
                              {exp.overallImprovementPercent != null
                                ? `${exp.overallImprovementPercent > 0 ? '+' : ''}${exp.overallImprovementPercent.toFixed(1)}%`
                                : '-'}
                            </div>
                          </div>
                        </>
                      )}
                    </div>

                    {/* Show baseline metrics when running (Phase 1.2) */}
                    {exp.status === 'RUNNING' && (exp.baselineMetrics || exp.baselineCacheHitRatio != null) && (
                      <div style={{ marginTop: '12px', padding: '8px', background: 'white', borderRadius: '4px' }}>
                        <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '4px' }}>Baseline Metrics</div>
                        <div style={{ display: 'flex', gap: '16px', fontSize: '12px', flexWrap: 'wrap' }}>
                          {exp.baselineLatencyP50 != null && <span>P50: {exp.baselineLatencyP50.toFixed(1)}ms</span>}
                          {exp.baselineLatencyP99 != null && <span>P99: {exp.baselineLatencyP99.toFixed(1)}ms</span>}
                          {exp.baselineThroughput != null && <span>Throughput: {exp.baselineThroughput.toFixed(0)} qps</span>}
                          {exp.baselineCacheHitRatio != null && <span>Cache Hit: {(exp.baselineCacheHitRatio * 100).toFixed(1)}%</span>}
                        </div>
                      </div>
                    )}

                    {/* Complete Experiment button for RUNNING status (Phase 1.4) */}
                    {exp.status === 'RUNNING' && (
                      <div style={{
                        marginTop: '12px',
                        padding: '12px',
                        background: '#fef3c7',
                        borderRadius: '6px',
                        border: '1px solid #fcd34d'
                      }}>
                        <div style={{
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                          gap: '12px'
                        }}>
                          <div>
                            <div style={{ fontSize: '13px', fontWeight: 500, marginBottom: '4px', color: '#92400e' }}>
                              Experiment Running
                            </div>
                            <div style={{ fontSize: '12px', color: '#a16207' }}>
                              Apply the configuration change, let workload stabilize, then complete to measure improvement.
                            </div>
                          </div>
                          <div style={{ display: 'flex', gap: '8px', flexShrink: 0 }}>
                            <button
                              onClick={() => handleCancelExperiment(exp.id)}
                              style={{
                                padding: '8px 12px',
                                background: 'transparent',
                                color: 'var(--color-danger)',
                                border: '1px solid var(--color-danger)',
                                borderRadius: '6px',
                                fontSize: '12px',
                                cursor: 'pointer'
                              }}
                            >
                              Cancel
                            </button>
                            <button
                              onClick={() => handleCompleteExperiment(exp.id)}
                              disabled={completingExperiment === exp.id}
                              style={{
                                padding: '8px 16px',
                                background: completingExperiment === exp.id ? 'var(--color-light-3)' : 'var(--color-dark-grey)',
                                color: 'white',
                                border: 'none',
                                borderRadius: '6px',
                                fontSize: '12px',
                                fontWeight: 500,
                                cursor: completingExperiment === exp.id ? 'not-allowed' : 'pointer',
                                display: 'flex',
                                alignItems: 'center',
                                gap: '6px'
                              }}
                            >
                              {completingExperiment === exp.id ? (
                                <>
                                  <Loader size={12} className={styles.spinner} />
                                  Measuring...
                                </>
                              ) : (
                                <>
                                  <CheckCircle size={12} />
                                  Complete Experiment
                                </>
                              )}
                            </button>
                          </div>
                        </div>

                        {/* Elapsed time since start */}
                        {(exp.startedAt || exp.createdAt) && (
                          <div style={{ marginTop: '8px', fontSize: '11px', color: '#a16207' }}>
                            Started: {new Date(exp.startedAt || exp.createdAt).toLocaleString()}
                            {' • '}
                            Elapsed: {formatElapsedTime(exp.startedAt || exp.createdAt)}
                          </div>
                        )}
                      </div>
                    )}

                    {exp.createdAt && exp.status !== 'RUNNING' && (
                      <div style={{ marginTop: '12px', fontSize: '12px', color: 'var(--color-light-6)' }}>
                        Created: {new Date(exp.createdAt).toLocaleString()}
                        {exp.completedAt && ` • Completed: ${new Date(exp.completedAt).toLocaleString()}`}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function ExperimentStatusIcon({ status }) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle size={16} style={{ color: 'var(--color-dark-grey)' }} />
    case 'RUNNING':
      return <Loader size={16} className={styles.spinner} style={{ color: 'var(--color-dark-grey)' }} />
    case 'FAILED':
      return <AlertTriangle size={16} style={{ color: 'var(--color-danger)' }} />
    default:
      return <Clock size={16} style={{ color: 'var(--color-light-6)' }} />
  }
}

function getStatusColor(status) {
  switch (status) {
    case 'COMPLETED':
      return { bg: 'var(--color-light-2)', text: 'var(--color-dark-grey)' }
    case 'RUNNING':
      return { bg: 'var(--color-light-2)', text: 'var(--color-dark-grey)' }
    case 'FAILED':
      return { bg: '#fee2e2', text: '#991b1b' }
    default:
      return { bg: 'var(--color-light-2)', text: 'var(--color-grey)' }
  }
}

export default ConfigTuningPanel
