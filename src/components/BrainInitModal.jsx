import { useState, useEffect, useRef, useCallback } from 'react'
import { X, CheckCircle, Loader2, XCircle, RotateCcw, ArrowRight } from 'lucide-react'
import { connectionAPI } from '@/lib/api/client'

const STAGES = [
  { key: 'SCHEMA_SCAN',    label: 'Scanning schema',       desc: 'Reading tables, columns, and relationships' },
  { key: 'DATA_SAMPLING',  label: 'Sampling data',         desc: 'Collecting column statistics and cardinality' },
  { key: 'KEY_COLUMN_ANALYSIS',   label: 'Analyzing key columns',   desc: 'Identifying important columns from query patterns' },
  { key: 'COLUMN_VALUE_COLLECTION', label: 'Caching value dictionaries', desc: 'Persisting low-cardinality filter values in VaultDB' },
  { key: 'INFERRED_RELATIONSHIPS', label: 'Inferring join paths', desc: 'Learning table joins from workload evidence and naming patterns' },
  { key: 'SCHEMA_CLASSIFICATION', label: 'Classifying schema',      desc: 'Detecting schema patterns and table roles' },
  { key: 'AI_DESCRIPTION', label: 'AI describing schema',  desc: 'Generating natural-language descriptions with the refreshed metadata graph' },
  { key: 'RAG_EMBEDDING',  label: 'Building search index', desc: 'Refreshing the retrieval index from schema, joins, and value summaries' },
  { key: 'BRAIN_ANALYSIS',        label: 'Brain analysis',          desc: 'Computing downstream reasoning signals on the refreshed metadata graph' },
  { key: 'SEMANTIC_MODELING',     label: 'Modeling semantics',      desc: 'Building the BI-ready semantic model in VaultDB' },
]

function deriveCompletedStages(stageTimings = {}) {
  return STAGES
    .filter((stage) => {
      const timing = stageTimings?.[stage.key]
      return Boolean(timing?.completedAt || timing?.endedAt || timing?.durationMs > 0)
    })
    .map((stage) => stage.key)
}

export default function BrainInitModal({
  connectionId,
  onClose,
  autoStart = true,
  closeOnDone = true,
}) {
  const [status, setStatus]         = useState(autoStart ? 'running' : 'idle')
  const [activeStage, setActiveStage] = useState(null)
  const [doneStages, setDoneStages]  = useState([])
  const [progress, setProgress]      = useState(0)
  const [errorMsg, setErrorMsg]      = useState('')
  const [hasExistingRun, setHasExistingRun] = useState(false)
  const pollRef = useRef(null)
  const closeTimerRef = useRef(null)

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
    if (closeTimerRef.current) {
      clearTimeout(closeTimerRef.current)
      closeTimerRef.current = null
    }
  }, [])

  const applySnapshot = useCallback((statusPayload, historyPayload = []) => {
    const currentStage = statusPayload?.currentStage || statusPayload?.stage || null
    const latestRun = Array.isArray(historyPayload) ? historyPayload[0] : null
    const stageTimings =
      (statusPayload?.stageTimings && Object.keys(statusPayload.stageTimings).length > 0)
        ? statusPayload.stageTimings
        : (latestRun?.stageTimings || {})
    const completedStages = deriveCompletedStages(stageTimings)
    const latestFinalStage = latestRun?.finalStage || latestRun?.currentStage || null
    const nextProgress = Number(
      statusPayload?.progressPercent
      ?? statusPayload?.progress
      ?? latestRun?.progressPercent
      ?? latestRun?.progress
      ?? (latestFinalStage === 'COMPLETED' ? 100 : 0),
    )
    const hasRun = Boolean(latestRun) || completedStages.length > 0

    setHasExistingRun(hasRun)

    if (currentStage && !['NONE', 'COMPLETED', 'FAILED', 'ERROR'].includes(currentStage)) {
      setStatus('running')
      setActiveStage(currentStage)
      setDoneStages(completedStages.filter((stage) => stage !== currentStage))
      setProgress(Math.max(0, Math.min(100, nextProgress)))
      setErrorMsg('')
      return
    }

    if (currentStage === 'FAILED' || currentStage === 'ERROR' || latestFinalStage === 'FAILED') {
      setStatus('error')
      setActiveStage(null)
      setDoneStages(completedStages)
      setProgress(Math.max(0, Math.min(100, nextProgress)))
      setErrorMsg(statusPayload?.errorMessage || latestRun?.errorMessage || 'Initialization failed')
      return
    }

    if (currentStage === 'COMPLETED' || latestFinalStage === 'COMPLETED') {
      setStatus('done')
      setActiveStage(null)
      setDoneStages(STAGES.map((stage) => stage.key))
      setProgress(100)
      setErrorMsg('')
      if (autoStart && closeOnDone && !closeTimerRef.current) {
        closeTimerRef.current = setTimeout(() => onClose?.(), 1500)
      }
      return
    }

    setStatus(hasRun ? 'done' : 'idle')
    setActiveStage(null)
    setDoneStages(completedStages)
    setProgress(Math.max(0, Math.min(100, nextProgress)))
    setErrorMsg('')
  }, [autoStart, closeOnDone, onClose])

  const loadExistingStatus = useCallback(async () => {
    if (!connectionId) return
    const [statusPayload, historyPayload] = await Promise.all([
      connectionAPI.getInitStatus(connectionId).catch(() => null),
      connectionAPI.getInitHistory(connectionId).catch(() => []),
    ])
    applySnapshot(statusPayload, historyPayload)
    return statusPayload
  }, [applySnapshot, connectionId])

  const startPolling = useCallback(() => {
    stopPolling()
    const poll = async () => {
      try {
        const nextStatus = await loadExistingStatus()
        const stage = nextStatus?.currentStage || nextStatus?.stage || ''
        if (!stage || ['COMPLETED', 'FAILED', 'ERROR', 'NONE'].includes(stage)) {
          stopPolling()
        }
      } catch {
        /* transient error - keep polling */
      }
    }

    void poll()
    pollRef.current = setInterval(poll, 3000)
  }, [loadExistingStatus, stopPolling])

  const runInit = useCallback(async () => {
    if (!connectionId) return
    stopPolling()
    try { await connectionAPI.reinitialize(connectionId) } catch { /* may already be running */ }
    setStatus('running')
    setErrorMsg('')
    setProgress(0)
    setDoneStages([])
    setActiveStage(null)
    startPolling()
  }, [connectionId, startPolling, stopPolling])

  const startInit = useCallback(async () => {
    if (!connectionId) return
    setStatus('running')
    setErrorMsg('')
    setProgress(0)
    setDoneStages([])
    setActiveStage(null)
    await runInit()
  }, [connectionId, runInit])

  useEffect(() => {
    if (autoStart) {
      void runInit()
    } else {
      void loadExistingStatus().then((nextStatus) => {
        const stage = nextStatus?.currentStage || nextStatus?.stage || ''
        if (stage && !['NONE', 'COMPLETED', 'FAILED', 'ERROR'].includes(stage)) {
          startPolling()
        }
      })
    }
    return () => stopPolling()
  }, [autoStart, loadExistingStatus, runInit, startPolling, stopPolling])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md mx-4 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <div>
            <h3 className="text-base font-semibold text-gray-900">
              {status === 'done' ? 'Brain status' : status === 'error' ? 'Brain init status' : 'Initializing Brain'}
            </h3>
            <p className="text-xs text-gray-500 mt-0.5">
              {status === 'done'
                ? 'Review the latest completed stages before refreshing.'
                : 'Analyzing your database schema'}
            </p>
          </div>
          {(status === 'done' || status === 'error' || status === 'idle' || !autoStart) && (
            <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 text-gray-400 hover:text-gray-600 transition-colors">
              <X size={16} />
            </button>
          )}
        </div>

        {/* Body */}
        <div className="px-6 py-5 space-y-5">
          {/* Progress bar */}
          <div className="space-y-1.5">
            <div className="flex justify-between text-xs text-gray-400">
              <span>Overall progress</span>
              <span>{progress}%</span>
            </div>
            <div className="h-1.5 bg-gray-100 rounded-full overflow-hidden">
              <div
                className="h-full bg-gray-900 rounded-full transition-all duration-500"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>

          {/* Stages */}
          <div className="space-y-2">
            {STAGES.map(stage => {
              const done    = doneStages.includes(stage.key)
              const active  = activeStage === stage.key
              const pending = !done && !active

              return (
                <div key={stage.key} className={`flex items-start gap-3 px-3 py-2.5 rounded-xl transition-all ${
                  active  ? 'bg-gray-50 border border-gray-200' :
                  done    ? 'opacity-60' : 'opacity-25'
                }`}>
                  <div className="w-5 h-5 flex-shrink-0 mt-0.5">
                    {done    && <CheckCircle size={18} className="text-green-500" />}
                    {active  && <Loader2    size={18} className="text-gray-900 animate-spin" />}
                    {pending && <div className="w-4 h-4 rounded-full border-2 border-gray-300 mt-0.5" />}
                  </div>
                  <div>
                    <p className={`text-sm font-medium ${active ? 'text-gray-900' : done ? 'text-gray-600' : 'text-gray-400'}`}>
                      {stage.label}
                    </p>
                    {active && <p className="text-xs text-gray-500 mt-0.5">{stage.desc}</p>}
                  </div>
                </div>
              )
            })}
          </div>

          {/* Done */}
          {status === 'done' && (
            <div className="space-y-3 rounded-xl bg-green-50 border border-green-100 px-4 py-3">
              <div className="flex items-center gap-2">
                <CheckCircle size={15} className="text-green-600 flex-shrink-0" />
                <p className="text-sm text-green-700 font-medium">Brain ready! You can start chatting.</p>
              </div>
              {!autoStart && (
                <button
                  type="button"
                  onClick={startInit}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-gray-900 text-white hover:bg-gray-700 transition-colors"
                >
                  <RotateCcw size={12} />
                  Refresh Brain
                </button>
              )}
            </div>
          )}

          {status === 'idle' && !hasExistingRun && (
            <div className="space-y-3 rounded-xl bg-gray-50 border border-gray-200 px-4 py-3">
              <div className="flex items-center gap-2">
                <ArrowRight size={15} className="text-gray-500 flex-shrink-0" />
                <p className="text-sm text-gray-700 font-medium">Brain has not been initialized yet.</p>
              </div>
              <button
                type="button"
                onClick={startInit}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-gray-900 text-white hover:bg-gray-700 transition-colors"
              >
                <RotateCcw size={12} />
                Initialize Brain
              </button>
            </div>
          )}

          {/* Error */}
          {status === 'error' && (
            <div className="rounded-xl bg-red-50 border border-red-100 px-4 py-3 space-y-3">
              <div className="flex items-center gap-2">
                <XCircle size={15} className="text-red-500 flex-shrink-0" />
                <p className="text-sm text-red-700 font-medium">Initialization failed</p>
              </div>
              {errorMsg && <p className="text-xs text-red-600 pl-5">{errorMsg}</p>}
              <div className="flex items-center gap-2 pl-5 pt-1">
                <button
                  type="button"
                  onClick={startInit}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-gray-900 text-white hover:bg-gray-700 transition-colors"
                >
                  <RotateCcw size={12} />
                  Retry
                </button>
                <button
                  type="button"
                  onClick={onClose}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors"
                >
                  Skip for now
                  <ArrowRight size={12} />
                </button>
              </div>
            </div>
          )}

          {status === 'running' && (
            <p className="text-xs text-gray-400 text-center">This usually takes 2–3 minutes. You can close this and the init will continue in the background.</p>
          )}
        </div>

        {/* Footer — only show dismiss while running */}
        {status === 'running' && (
          <div className="px-6 pb-4">
            <button
              onClick={onClose}
              className="w-full py-2 text-sm text-gray-500 hover:text-gray-700 hover:bg-gray-50 rounded-xl transition-colors"
            >
              Run in background
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
