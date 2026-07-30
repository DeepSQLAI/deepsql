import { useEffect, useState, useRef, useCallback } from 'react'
import { CheckCircle, Loader2, XCircle, AlertCircle, RotateCcw, ArrowRight } from 'lucide-react'
import { connectionAPI } from '@/lib/api/client'

const STAGES = [
  { key: 'SCHEMA_SCAN',   label: 'Scanning schema',         desc: 'Reading tables, columns, and relationships' },
  { key: 'DATA_SAMPLING', label: 'Sampling data',           desc: 'Collecting column statistics and cardinality' },
  { key: 'KEY_COLUMN_ANALYSIS', label: 'Analyzing key columns', desc: 'Identifying important columns from query patterns' },
  { key: 'COLUMN_VALUE_COLLECTION', label: 'Caching value dictionaries', desc: 'Persisting low-cardinality filter values in VaultDB' },
  { key: 'INFERRED_RELATIONSHIPS', label: 'Inferring join paths', desc: 'Learning table joins from workload evidence and naming patterns' },
  { key: 'SCHEMA_CLASSIFICATION', label: 'Classifying schema', desc: 'Detecting schema patterns and table roles' },
  { key: 'AI_DESCRIPTION',label: 'AI describing schema',    desc: 'Generating natural-language descriptions with the refreshed metadata graph' },
  { key: 'RAG_EMBEDDING', label: 'Building search index',   desc: 'Refreshing the retrieval index from schema, joins, and value summaries' },
  { key: 'BRAIN_ANALYSIS',label: 'Brain analysis',          desc: 'Computing downstream reasoning signals on the refreshed metadata graph' },
  { key: 'SEMANTIC_MODELING', label: 'Modeling semantics', desc: 'Building the BI-ready semantic model in VaultDB' },
]

export default function StepBrainInit({ connectionId, onComplete }) {
  const [status,       setStatus]       = useState(connectionId ? 'running' : 'waiting')  // waiting | running | done | error
  const [activeStage,  setActiveStage]  = useState(null)
  const [doneStages,   setDoneStages]   = useState([])
  const [progress,     setProgress]     = useState(0)
  const [errorMsg,     setErrorMsg]     = useState('')
  const pollRef = useRef(null)

  const startPolling = useCallback(() => {
    pollRef.current = setInterval(async () => {
      try {
        const s = await connectionAPI.getInitStatus(connectionId)
        if (!s) return

        const stage       = s.currentStage || s.stage || ''
        const prog        = s.progressPercent ?? s.progress ?? 0
        const isCompleted = stage === 'COMPLETED' || prog >= 100
        const isFailed    = stage === 'FAILED' || stage === 'ERROR'
        const timings     = s.stageTimings || {}

        if (isCompleted) {
          clearInterval(pollRef.current)
          setDoneStages(STAGES.map(st => st.key))
          setActiveStage(null)
          setProgress(100)
          setStatus('done')
          setTimeout(() => onComplete?.(), 1200)
          return
        }

        if (isFailed) {
          clearInterval(pollRef.current)
          const lastAttempted = [...STAGES].reverse().find(st => timings[st.key]?.startedAt)
          const failedKey = lastAttempted?.key
          const failedIdx = failedKey ? STAGES.findIndex(st => st.key === failedKey) : -1
          const doneKeys = failedIdx > 0 ? STAGES.slice(0, failedIdx).map(st => st.key) : []
          setDoneStages(doneKeys)
          setActiveStage(null)
          setProgress(prog)
          setStatus('error')
          setErrorMsg(s.errorMessage || 'Initialization failed')
          return
        }

        setActiveStage(stage)
        setProgress(prog)

        const stageIdx = STAGES.findIndex(st => st.key === stage)
        if (stageIdx > 0) {
          setDoneStages(STAGES.slice(0, stageIdx).map(st => st.key))
        }
      } catch {
        // Transient network error - keep polling
      }
    }, 3000)
  }, [connectionId, onComplete])

  const runInit = useCallback(async () => {
    if (!connectionId) return
    clearInterval(pollRef.current)

    try {
      await connectionAPI.reinitialize(connectionId)
    } catch {
      // May already be running; continue polling
    }
    startPolling()
  }, [connectionId, startPolling])

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
    void runInit()
    return () => clearInterval(pollRef.current)
  }, [runInit])

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-semibold text-gray-900 tracking-tight">Initializing Brain</h2>
        <p className="mt-2 text-sm text-gray-500">
          DeepSQL is analyzing your database. This usually takes 2–5 minutes.
        </p>
      </div>

      {!connectionId && (
        <div className="flex items-center gap-2 rounded-lg bg-amber-50 border border-amber-100 px-4 py-3">
          <AlertCircle size={15} className="text-amber-500 flex-shrink-0" />
          <p className="text-sm text-amber-700">
            No database connection found. Please go back and connect a database first.
          </p>
        </div>
      )}

      {/* Progress bar */}
      {connectionId && (
        <div className="space-y-1">
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
      )}

      {/* Stages */}
      {connectionId && (
        <div className="space-y-3">
          {STAGES.map(stage => {
            const done    = doneStages.includes(stage.key)
            const active  = activeStage === stage.key
            const pending = !done && !active

            return (
              <div key={stage.key} className={`flex items-start gap-3 p-3 rounded-lg transition-all ${
                active  ? 'bg-gray-50 border border-gray-200' :
                done    ? 'opacity-70' : 'opacity-30'
              }`}>
                <div className="w-5 h-5 flex-shrink-0 mt-0.5">
                  {done   && <CheckCircle size={18} className="text-green-500" />}
                  {active && <Loader2    size={18} className="text-gray-900 animate-spin" />}
                  {pending && (
                    <div className="w-4.5 h-4.5 rounded-full border-2 border-gray-200 mt-0.5" />
                  )}
                </div>
                <div>
                  <p className={`text-sm font-medium ${active ? 'text-gray-900' : done ? 'text-gray-600' : 'text-gray-400'}`}>
                    {stage.label}
                  </p>
                  {active && (
                    <p className="text-xs text-gray-500 mt-0.5">{stage.desc}</p>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}

      {/* Done */}
      {status === 'done' && (
        <div className="flex items-center gap-2 rounded-lg bg-green-50 border border-green-100 px-4 py-3">
          <CheckCircle size={15} className="text-green-600 flex-shrink-0" />
          <p className="text-sm text-green-700 font-medium">
            Brain initialized! Proceeding to dashboard…
          </p>
        </div>
      )}

      {/* Error */}
      {status === 'error' && (
        <div className="rounded-lg bg-red-50 border border-red-100 px-4 py-3 space-y-3">
          <div className="flex items-center gap-2">
            <XCircle size={15} className="text-red-500 flex-shrink-0" />
            <p className="text-sm text-red-700 font-medium">Initialization failed</p>
          </div>
          {errorMsg && <p className="text-xs text-red-600 pl-5">{errorMsg}</p>}
          <p className="text-xs text-gray-500 pl-5">
            You can retry from the dashboard or proceed anyway — Brain can be re-initialized later.
          </p>
          <div className="flex items-center gap-2 pl-5 pt-1">
            <button
              type="button"
              onClick={startInit}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium !bg-gray-900 !text-white hover:!bg-gray-700 transition-colors"
            >
              <RotateCcw size={12} />
              Retry
            </button>
            <button
              type="button"
              onClick={() => onComplete?.()}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors"
            >
              Proceed anyway
              <ArrowRight size={12} />
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
