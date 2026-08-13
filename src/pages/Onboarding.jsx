import { useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { CheckCircle, Circle } from 'lucide-react'
import StepWelcome    from '@/components/onboarding/StepWelcome'
import StepDatabase   from '@/components/onboarding/StepDatabase'
import StepQueryLogs  from '@/components/onboarding/StepQueryLogs'
import StepLLMConfig  from '@/components/onboarding/StepLLMConfig'
import StepBrainInit  from '@/components/onboarding/StepBrainInit'
import StepComplete   from '@/components/onboarding/StepComplete'
import { setupAPI, connectionAPI } from '@/lib/api/client'

// ─── Step definitions ───────────────────────────────────────────────────────
const STEPS = [
  { id: 'welcome',   label: 'Welcome',      optional: false },
  { id: 'database',  label: 'Database',     optional: false },
  { id: 'logs',      label: 'Query logs',   optional: true  },
  { id: 'llm',       label: 'AI config',    optional: false },
  { id: 'brain',     label: 'Brain init',   optional: false },
  { id: 'done',      label: 'All set',      optional: false },
]

// ─── Sidebar step indicator ──────────────────────────────────────────────────
function Stepper({ currentIndex }) {
  return (
    <nav className="flex flex-col gap-1">
      {STEPS.map((step, idx) => {
        const done    = idx < currentIndex
        const active  = idx === currentIndex
        const pending = idx > currentIndex

        return (
          <div
            key={step.id}
            className={`flex items-center gap-3 px-3 py-2.5 rounded-lg transition-colors ${
              active  ? 'bg-gray-900 text-white' :
              done    ? 'text-gray-600' :
              'text-gray-400'
            }`}
          >
            <div className="w-5 h-5 flex-shrink-0 flex items-center justify-center">
              {done   && <CheckCircle size={16} className="text-green-500" />}
              {active && <div className="w-2 h-2 rounded-full bg-white" />}
              {pending && <Circle size={16} className="text-gray-300" />}
            </div>
            <span className="text-sm font-medium leading-none">{step.label}</span>
            {step.optional && pending && (
              <span className="ml-auto text-[10px] text-gray-400 font-medium">optional</span>
            )}
          </div>
        )
      })}
    </nav>
  )
}

// ─── Main wizard ─────────────────────────────────────────────────────────────
export default function Onboarding() {
  const navigate = useNavigate()

  const [stepIndex, setStepIndex]     = useState(0)
  const [saving,    setSaving]        = useState(false)
  const [saveError, setSaveError]     = useState('')

  // Per-step form data
  const [welcomeData,  setWelcomeData]  = useState({ orgName: '', teamSize: '' })
  const [dbData,       setDbData]       = useState({ dbType: 'postgres', host: '', port: '5432', database: '', username: '', password: '', sslMode: 'prefer', tested: false })
  const [logsData,     setLogsData]     = useState({ logSource: 'pg_stat', skipped: false })
  const [llmData,      setLlmData]      = useState({ provider: 'openai', apiKey: '', endpoint: '', chatModel: 'gpt-4o', configured: false })

  // Tracks the connection created in StepDatabase (needed by StepBrainInit)
  const [connectionId, setConnectionId] = useState(null)

  // ── Navigation helpers ──────────────────────────────────────────────────
  const next = useCallback(() => setStepIndex(i => Math.min(i + 1, STEPS.length - 1)), [])
  const back = useCallback(() => { setSaveError(''); setStepIndex(i => Math.max(i - 1, 0)) }, [])

  // ── Per-step "Continue" logic ───────────────────────────────────────────
  async function handleContinue() {
    setSaveError('')
    setSaving(true)
    try {
      const current = STEPS[stepIndex].id

      if (current === 'welcome') {
        // Best-effort — org info is optional, never block wizard progress
        setupAPI.saveOrganization({
          orgName:  welcomeData.orgName.trim(),
          teamSize: welcomeData.teamSize,
        }).catch(() => {})
        next()

      } else if (current === 'database') {
        if (!dbData.tested) {
          setSaveError('Please test your database connection before continuing.')
          return
        }
        // Create the connection so Brain init has a connectionId. Field names
        // must match ConnectionRequest exactly (connectionName / dbType /
        // database) — the wizard previously sent name/databaseType/databaseName,
        // which ConnectionRequest silently ignored (they deserialize to null).
        try {
          const created = await connectionAPI.saveConnection({
            connectionName: dbData.database || dbData.host,
            dbType:         dbData.dbType,
            host:           dbData.host,
            port:           parseInt(dbData.port) || 5432,
            database:       dbData.database,
            username:       dbData.username,
            password:       dbData.password,
            sslMode:        dbData.sslMode,
          })
          setConnectionId(created.id ?? created.connectionId)
        } catch (err) {
          // If connection already exists (idempotent), try listing and picking first
          const list = await connectionAPI.getAllConnections()
          if (list?.length) setConnectionId(list[0].id)
          else throw err
        }
        next()

      } else if (current === 'logs') {
        // Always skippable — just advance
        next()

      } else if (current === 'llm') {
        if (!llmData.configured) {
          setSaveError('Please test and verify your API key before continuing.')
          return
        }
        await setupAPI.saveLlmConfig({
          provider:  llmData.provider,
          apiKey:    llmData.apiKey,
          endpoint:  llmData.endpoint || undefined,
          chatModel: llmData.chatModel,
        })
        next()

      } else if (current === 'brain') {
        // Brain step is auto-advancing — "Continue" is hidden
        next()

      } else if (current === 'done') {
        await setupAPI.markComplete()
        navigate('/dashboard', { replace: true })
      }
    } catch (err) {
      setSaveError(err.message || 'Something went wrong. Please try again.')
    } finally {
      setSaving(false)
    }
  }

  // ── "Skip" for optional steps ───────────────────────────────────────────
  function handleSkip() {
    setSaveError('')
    next()
  }

  // ── Brain init auto-advance ─────────────────────────────────────────────
  const handleBrainComplete = useCallback(() => {
    next()
  }, [next])

  // ── "Open Dashboard" from final step ───────────────────────────────────
  async function handleFinish() {
    setSaving(true)
    try {
      await setupAPI.markComplete()
      navigate('/dashboard', { replace: true })
    } catch {
      navigate('/dashboard', { replace: true })
    } finally {
      setSaving(false)
    }
  }

  // ── Render current step content ─────────────────────────────────────────
  function renderStep() {
    const current = STEPS[stepIndex].id

    if (current === 'welcome')
      return <StepWelcome  data={welcomeData} onUpdate={setWelcomeData} />

    if (current === 'database')
      return <StepDatabase data={dbData}      onUpdate={setDbData} />

    if (current === 'logs')
      return <StepQueryLogs data={logsData}   onUpdate={setLogsData} />

    if (current === 'llm')
      return <StepLLMConfig data={llmData}    onUpdate={setLlmData} />

    if (current === 'brain')
      return <StepBrainInit connectionId={connectionId} onComplete={handleBrainComplete} />

    if (current === 'done')
      return <StepComplete  orgName={welcomeData.orgName} onFinish={handleFinish} />

    return null
  }

  const current       = STEPS[stepIndex].id
  const isOptional    = STEPS[stepIndex].optional
  const isLastStep    = stepIndex === STEPS.length - 1
  const isBrainStep   = current === 'brain'
  const isDoneStep    = current === 'done'

  // Continue button label
  const continueLabel = saving
    ? 'Saving…'
    : isLastStep ? 'Finish' : 'Continue'

  // ── Validate to enable Continue ────────────────────────────────────────
  const canContinue = (() => {
    if (saving) return false
    if (current === 'welcome')  return true  // org name + team size are optional
    if (current === 'database') return dbData.tested
    if (current === 'logs')     return true        // always allowed (can skip)
    if (current === 'llm')      return llmData.configured
    return true
  })()

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
      <div className="w-full max-w-3xl bg-white rounded-2xl shadow-sm border border-gray-100 overflow-hidden flex">

        {/* ── Left sidebar ─────────────────────────────────────────────── */}
        <div className="w-52 flex-shrink-0 bg-gray-50 border-r border-gray-100 p-6 flex flex-col">
          {/* Logo / brand */}
          <div className="mb-8">
            <div className="w-8 h-8 rounded-lg bg-gray-900 flex items-center justify-center mb-3">
              <span className="text-white text-xs font-bold">D</span>
            </div>
            <p className="text-xs font-semibold text-gray-900 tracking-tight">DeepSQL</p>
            <p className="text-[10px] text-gray-400 mt-0.5">Setup wizard</p>
          </div>

          <Stepper currentIndex={stepIndex} />

          <div className="mt-auto pt-6">
            <p className="text-[10px] text-gray-400 leading-relaxed">
              Takes about 5–10 min.<br />
              You can revisit settings from the dashboard anytime.
            </p>
          </div>
        </div>

        {/* ── Right content area ───────────────────────────────────────── */}
        <div className="flex-1 flex flex-col">
          {/* Step content */}
          <div className="flex-1 p-8 overflow-y-auto">
            {renderStep()}
          </div>

          {/* Footer actions */}
          {!isDoneStep && (
            <div className="px-8 py-4 border-t border-gray-100 flex items-center justify-between bg-white">
              {/* Back */}
              <button
                type="button"
                onClick={back}
                disabled={stepIndex === 0 || saving}
                className={`text-sm font-medium transition-colors ${
                  stepIndex === 0
                    ? 'text-gray-200 cursor-not-allowed'
                    : 'text-gray-500 hover:text-gray-900'
                }`}
              >
                Back
              </button>

              <div className="flex items-center gap-3">
                {/* Save error */}
                {saveError && (
                  <p className="text-xs text-red-500 max-w-xs text-right">{saveError}</p>
                )}

                {/* Skip (optional steps only, except brain which auto-advances) */}
                {isOptional && !isBrainStep && (
                  <button
                    type="button"
                    onClick={handleSkip}
                    disabled={saving}
                    className="text-sm text-gray-400 hover:text-gray-600 transition-colors"
                  >
                    Skip for now
                  </button>
                )}

                {/* Continue (hidden on brain step — it auto-advances) */}
                {!isBrainStep && (
                  <button
                    type="button"
                    onClick={handleContinue}
                    disabled={!canContinue}
                    className={`px-5 py-2 rounded-lg text-sm font-medium transition-all ${
                      canContinue
                        ? '!bg-gray-900 !text-white hover:!bg-gray-700'
                        : '!bg-gray-100 text-gray-300 cursor-not-allowed'
                    }`}
                  >
                    {continueLabel}
                  </button>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
