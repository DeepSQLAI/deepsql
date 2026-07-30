import { useState, useCallback, useEffect, useRef } from 'react'
import {
  Brain, Plus, Play, Pencil, Trash2, Clock,
  RotateCcw, CheckCircle, XCircle,
  ToggleLeft, ToggleRight, ArrowUp, Eraser, Lock, Search,
  Rocket, FlaskConical, CheckCheck,
} from 'lucide-react'
import { chatAPI } from '@/lib/api/client'
import {
  getAgents, saveAgent, deleteAgent, toggleAgent,
  getRuns, addRun, updateRun, clearRuns,
  getChatId, setChatId, generateId,
  deployAgent, undeployAgent, getNextRunDate, isDue,
} from './brainAgentStore'
import CreateAgentModal from './CreateAgentModal'
import AgentArtifacts from './AgentArtifacts'
import styles from './BrainView.module.css'

// ── Status messages shown while the agent is working ─────────────────────────

const WORKING_STATUSES = [
  'Examining your schema…',
  'Running analysis queries…',
  'Cross-referencing tables…',
  'Aggregating results…',
  'Identifying patterns…',
  'Compiling insights…',
  'Preparing your briefing…',
]

function useRotatingStatus(isActive) {
  const [statusIdx, setStatusIdx] = useState(0)

  useEffect(() => {
    if (!isActive) { setStatusIdx(0); return }
    const id = setInterval(() => {
      setStatusIdx((i) => (i + 1) % WORKING_STATUSES.length)
    }, 2200)
    return () => clearInterval(id)
  }, [isActive])

  return WORKING_STATUSES[statusIdx]
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function scheduleLabel(schedule, scheduleTime) {
  switch (schedule) {
    case 'hourly':  return 'Every hour'
    case 'daily':   return scheduleTime ? `Daily ${scheduleTime}` : 'Daily'
    case 'weekly':  return scheduleTime ? `Weekly Mon ${scheduleTime}` : 'Weekly'
    case 'monthly': return scheduleTime ? `Monthly 1st ${scheduleTime}` : 'Monthly'
    default:        return 'Manual'
  }
}

function formatRelative(iso) {
  if (!iso) return null
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1)   return 'just now'
  if (mins < 60)  return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24)   return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}

function formatDuration(ms) {
  if (!ms) return null
  if (ms < 1000)  return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`
}

// Text rendering is handled inside AgentArtifacts; no separate RenderMarkdown needed here.

// ── Example agents ────────────────────────────────────────────────────────────

const EXAMPLE_AGENTS = [
  {
    name: 'BI Agent',
    desc: 'Monitor new customer engagement',
    task: 'Find all new customers added in the last 30 days and share insights on their product engagement — which features they use most, where they drop off, and how they compare to older cohorts.',
    schedule: 'daily',
  },
  {
    name: 'Accounts Agent',
    desc: 'Detect overdue invoices',
    task: 'Check all subscription invoices raised and detect any accounts that have not paid for more than 30 days. Include account names, amounts, and days overdue.',
    schedule: 'daily',
  },
  {
    name: 'Logs Agent',
    desc: 'Detect unusual user activity',
    task: 'Mine all user activity logs from the past 24 hours and detect any unusual patterns — failed logins, off-hours access, high request volumes from single users, or API error spikes.',
    schedule: 'hourly',
  },
]

// ── WorkingIndicator — shown inside the conversation while agent is processing ─

function WorkingIndicator({ connectionName }) {
  const status = useRotatingStatus(true)

  return (
    <div className={styles.turnAgent}>
      <div className={styles.agentAvatar}>
        <Brain size={13} color="#fff" />
      </div>
      <div className={styles.workingBubble}>
        <div className={styles.workingDots}>
          <span className={styles.thinkingDot} />
          <span className={styles.thinkingDot} />
          <span className={styles.thinkingDot} />
        </div>
        <span className={styles.workingStatus}>{status}</span>
        {connectionName && (
          <span className={styles.workingConn}>· {connectionName}</span>
        )}
      </div>
    </div>
  )
}

// ── ClarificationCard — rendered when agent needs clarification ───────────────

function ClarificationCard({ text }) {
  // Parse numbered questions from the [ASK] block
  const lines = text.replace(/^\[ASK\]\s*/i, '').trim().split('\n').filter(Boolean)
  const questions = lines.filter((l) => /^\d+\./.test(l.trim()))
  const preamble  = lines.filter((l) => !/^\d+\./.test(l.trim())).join(' ').trim()

  return (
    <div className={styles.clarificationCard}>
      <div className={styles.clarificationIcon}>?</div>
      <div className={styles.clarificationBody}>
        {preamble && <p className={styles.clarificationPreamble}>{preamble}</p>}
        <ol className={styles.clarificationList}>
          {questions.map((q, i) => (
            <li key={i} className={styles.clarificationItem}>
              {q.replace(/^\d+\.\s*/, '')}
            </li>
          ))}
        </ol>
        <p className={styles.clarificationHint}>Reply below to continue ↓</p>
      </div>
    </div>
  )
}

// ── PlanSteps — animated plan step list shown during/after execution ──────────

function PlanSteps({ plan, done = false }) {
  if (!plan) return null
  const steps = plan.split('\n').filter(Boolean).map((l) => l.replace(/^\d+\.\s*/, '').trim())
  const [activeStep, setActiveStep] = useState(0)

  useEffect(() => {
    if (done) { setActiveStep(steps.length); return }
    const id = setInterval(() => setActiveStep((s) => Math.min(s + 1, steps.length - 1)), 3500)
    return () => clearInterval(id)
  }, [done, steps.length])

  return (
    <div className={styles.planSteps}>
      {steps.map((step, i) => {
        const isCompleted = done || i < activeStep
        const isCurrent   = !done && i === activeStep
        return (
          <div key={i} className={`${styles.planStep} ${isCompleted ? styles.planStepDone : ''} ${isCurrent ? styles.planStepActive : ''}`}>
            <span className={styles.planStepDot} />
            <span className={styles.planStepText}>{step}</span>
          </div>
        )
      })}
    </div>
  )
}

// ── ConversationTurn: one user → agent exchange ───────────────────────────────

function ConversationTurn({ run }) {
  const [activeTab, setActiveTab] = useState('output')

  const isClarification = run.status === 'completed' &&
    run.output && /^\[ASK\]/i.test(run.output.trim())

  const StatusIcon  = run.status === 'completed' ? CheckCircle : XCircle
  const statusColor = run.status === 'completed' ? '#10b981' : '#ef4444'
  const hasInspect  = run.status === 'completed' && !isClarification &&
    (run.queries?.length > 0 || run.plan)

  return (
    <div className={styles.turn}>
      {/* User bubble */}
      <div className={styles.turnUser}>
        <div className={styles.userBubble}>{run.userMessage}</div>
      </div>

      {/* Agent bubble — only rendered when done */}
      {run.status !== 'running' && (
        <div className={styles.turnAgent}>
          <div className={styles.agentAvatar}>
            <Brain size={13} color="#fff" />
          </div>
          <div className={styles.agentBubble}>
            <div className={styles.agentBubbleMeta}>
              <StatusIcon size={11} color={statusColor} />
              <span className={styles.agentBubbleTime}>
                {new Date(run.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
              </span>
              {run.duration && (
                <span className={styles.agentBubbleDuration}>{formatDuration(run.duration)}</span>
              )}
              {run.isTest && (
                <span className={styles.runBadgeTest}><FlaskConical size={9} /> Test</span>
              )}
              {run.isScheduled && (
                <span className={styles.runBadgeScheduled}><Rocket size={9} /> Scheduled</span>
              )}
              {hasInspect && (
                <div className={styles.tabBar}>
                  <button
                    className={`${styles.tabBtn} ${activeTab === 'output' ? styles.tabBtnActive : ''}`}
                    onClick={() => setActiveTab('output')}
                  >
                    Output
                  </button>
                  <button
                    className={`${styles.tabBtn} ${activeTab === 'inspect' ? styles.tabBtnActive : ''}`}
                    onClick={() => setActiveTab('inspect')}
                  >
                    <Search size={10} />
                    Inspect
                  </button>
                </div>
              )}
            </div>

            <div className={styles.agentBubbleContent}>
              {isClarification ? (
                <ClarificationCard text={run.output} />
              ) : activeTab === 'output' || !hasInspect ? (
                <AgentArtifacts content={run.output || '(no output)'} />
              ) : (
                <InspectPanel
                  plan={run.plan}
                  queries={run.queries}
                  rowCount={run.rowCount}
                  connectionName={run.connectionName}
                  queriedAt={run.startedAt}
                />
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ── InspectPanel: shows plan + executed SQL for debugging ─────────────────────

function InspectPanel({ plan, queries, rowCount, connectionName, queriedAt }) {
  const rowLabel = rowCount === null || rowCount === undefined
    ? null
    : rowCount === 0
      ? 'No rows returned'
      : `${rowCount} row${rowCount === 1 ? '' : 's'} returned`

  return (
    <div className={styles.inspectPanel}>

      {/* ── Execution context strip ── */}
      {(connectionName || queriedAt || rowLabel) && (
        <div className={styles.inspectMeta}>
          {connectionName && (
            <span className={styles.inspectMetaItem}>
              <span className={styles.inspectMetaLabel}>Database</span>
              <span className={styles.inspectMetaValue}>{connectionName}</span>
            </span>
          )}
          {queriedAt && (
            <span className={styles.inspectMetaItem}>
              <span className={styles.inspectMetaLabel}>Queried at</span>
              <span className={styles.inspectMetaValue}>
                {new Date(queriedAt).toLocaleString([], {
                  month: 'short', day: 'numeric',
                  hour: '2-digit', minute: '2-digit', second: '2-digit',
                })}
              </span>
            </span>
          )}
          {rowLabel && (
            <span className={`${styles.inspectMetaItem} ${rowCount === 0 ? styles.inspectMetaZero : styles.inspectMetaRows}`}>
              <span className={styles.inspectMetaValue}>{rowLabel}</span>
            </span>
          )}
        </div>
      )}

      {plan && (
        <div className={styles.inspectSection}>
          <div className={styles.inspectSectionTitle}>Analysis Plan</div>
          <div className={styles.inspectPlan}>
            {plan.split('\n').filter(Boolean).map((line, i) => (
              <div key={i} className={styles.inspectPlanStep}>{line}</div>
            ))}
          </div>
        </div>
      )}

      {queries?.length > 0 && (
        <div className={styles.inspectSection}>
          <div className={styles.inspectSectionTitle}>
            {queries.length === 1 ? 'Query Executed' : `Queries Executed (${queries.length})`}
          </div>
          {queries.map((q, i) => (
            <div key={i} className={styles.inspectQuery}>
              {queries.length > 1 && (
                <div className={styles.inspectQueryLabel}>Query {i + 1}</div>
              )}
              <pre className={styles.inspectCode}>{q.trim()}</pre>
            </div>
          ))}
        </div>
      )}

      {!plan && (!queries || queries.length === 0) && (
        <div className={styles.inspectEmpty}>No inspection data available for this run.</div>
      )}
    </div>
  )
}

// ── Main component ────────────────────────────────────────────────────────────

export default function BrainView({ connections }) {
  const [agents, setAgents]         = useState(() => getAgents())
  const [selectedId, setSelectedId] = useState(null)
  const [detailTab, setDetailTab]   = useState('runs')
  const [showCreate, setShowCreate] = useState(false)
  const [editAgent, setEditAgent]   = useState(null)
  const [runs, setRuns]             = useState([])
  const [runningIds, setRunningIds] = useState(new Set())
  const [welcomeExample, setWelcomeExample] = useState(null)
  const [reply, setReply]           = useState('')

  const scrollRef = useRef(null)
  const replyRef  = useRef(null)

  const refreshAgents = useCallback(() => setAgents(getAgents()), [])
  const refreshRuns   = useCallback((id) => setRuns(getRuns(id)), [])

  useEffect(() => {
    if (selectedId) refreshRuns(selectedId)
  }, [selectedId, refreshRuns])

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [runs, runningIds])

  const autoResize = useCallback(() => {
    const ta = replyRef.current
    if (!ta) return
    ta.style.height = 'auto'
    ta.style.height = `${Math.min(ta.scrollHeight, 140)}px`
  }, [])

  // ── Core send logic ───────────────────────────────────────────────────────

  const sendMessage = useCallback(async (agent, userMessage, { isTest = false, isScheduled = false } = {}) => {
    if (runningIds.has(agent.id) || !userMessage.trim()) return

    const runId  = generateId()
    const chatId = getChatId(agent.id)

    addRun(agent.id, {
      id: runId,
      userMessage: userMessage.trim(),
      startedAt: new Date().toISOString(),
      status: 'running',
      output: null,
      duration: null,
      isTest,
      isScheduled,
    })

    setRunningIds((prev) => new Set([...prev, agent.id]))
    if (selectedId === agent.id) refreshRuns(agent.id)

    const connName = connections.find((c) => c.id === agent.connectionId)?.connectionName || agent.connectionId
    const startTime = Date.now()
    try {
      const response = await chatAPI.sendMessage(
        agent.connectionId,
        userMessage.trim(),
        null,        // threadId
        chatId       // chatId
      )
      const duration = Date.now() - startTime

      if (response.chatId) setChatId(agent.id, response.chatId)

      // Collect all executed SQL — prefer multi-query list, fall back to single sql field
      const executedQueries = response.executedQueries?.length
        ? response.executedQueries
        : response.sql ? [response.sql] : []

      // Row count: use first query result (response.data) or infer from executedQueries presence
      const rowCount = response.data?.rowCount ?? null

      updateRun(agent.id, runId, {
        status: 'completed',
        output: response.message || '(no output)',
        duration,
        plan: response.plan || null,
        queries: executedQueries,
        rowCount,
        connectionName: connName,
      })
    } catch (err) {
      const duration = Date.now() - startTime
      updateRun(agent.id, runId, {
        status: 'failed',
        output: err?.response?.data?.message || err?.message || 'Something went wrong.',
        duration,
        connectionName: connName,
      })
    } finally {
      setRunningIds((prev) => {
        const next = new Set(prev)
        next.delete(agent.id)
        return next
      })
      if (selectedId === agent.id) refreshRuns(agent.id)
    }
  }, [runningIds, selectedId, refreshRuns])

  // ── Frontend scheduler — fires deployed agents on their schedule ───────────

  useEffect(() => {
    const check = () => {
      const allAgents = getAgents()
      const now = new Date()
      allAgents.forEach((agent) => {
        if (!agent.deployed || !agent.enabled || runningIds.has(agent.id)) return
        const agentRuns = getRuns(agent.id).filter((r) => r.status !== 'running')
        const lastRun   = agentRuns[agentRuns.length - 1]
        const lastRunTime = lastRun ? new Date(lastRun.startedAt) : null
        if (isDue(agent, now, lastRunTime)) {
          sendMessage(agent, agent.task, { isScheduled: true })
        }
      })
    }
    const interval = setInterval(check, 60_000)
    return () => clearInterval(interval)
  }, [runningIds, sendMessage])

  // ── Handlers ─────────────────────────────────────────────────────────────

  const handleSaveAgent = useCallback((agent) => {
    saveAgent(agent)
    refreshAgents()
    setShowCreate(false)
    setEditAgent(null)
    setSelectedId(agent.id)
    setDetailTab('runs')
  }, [refreshAgents])

  const handleDelete = useCallback((id) => {
    if (!window.confirm('Delete this agent and all its conversation history?')) return
    deleteAgent(id)
    refreshAgents()
    if (selectedId === id) setSelectedId(null)
  }, [selectedId, refreshAgents])

  const handleToggle = useCallback((id) => {
    toggleAgent(id)
    refreshAgents()
  }, [refreshAgents])

  // Test: runs the task in conversation mode, marks run as isTest
  const handleTest = useCallback((agent) => {
    sendMessage(agent, agent.task, { isTest: true })
  }, [sendMessage])

  // Deploy: captures the last successful output as the golden reference,
  // marks agent as deployed (enables scheduling), then fires first run.
  const handleDeploy = useCallback((agent) => {
    const allRuns = getRuns(agent.id)
    const lastGood = [...allRuns].reverse().find((r) => r.status === 'completed')
    const updated = deployAgent(agent.id, lastGood?.output || null)
    refreshAgents()
    if (updated) sendMessage(updated, updated.task, { isScheduled: true })
  }, [sendMessage, refreshAgents])

  const handleUndeploy = useCallback((id) => {
    undeployAgent(id)
    refreshAgents()
  }, [refreshAgents])

  const handleReply = useCallback(() => {
    if (!reply.trim() || !selectedId) return
    const agent = agents.find((a) => a.id === selectedId)
    if (!agent) return
    sendMessage(agent, reply.trim())
    setReply('')
    if (replyRef.current) replyRef.current.style.height = 'auto'
  }, [reply, selectedId, agents, sendMessage])

  const handleReplyKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleReply()
    }
  }

  const handleClearHistory = useCallback(() => {
    if (!selectedId) return
    if (!window.confirm('Clear all conversation history for this agent?')) return
    clearRuns(selectedId)
    refreshRuns(selectedId)
  }, [selectedId, refreshRuns])

  const handleWelcomeExample = useCallback((example) => {
    setWelcomeExample(example)
    setShowCreate(true)
  }, [])

  const selectedAgent      = agents.find((a) => a.id === selectedId) || null
  const selectedConnection = connections.find((c) => c.id === selectedAgent?.connectionId)
  const isRunning          = selectedAgent && runningIds.has(selectedAgent.id)
  const hasRuns            = runs.length > 0
  const isDeployed         = selectedAgent?.deployed === true
  const nextRun            = selectedAgent ? getNextRunDate(selectedAgent) : null

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className={styles.root}>

      {/* ── Sidebar ─────────────────────────────────────────────────────────── */}
      <div className={styles.sidebar}>
        <div className={styles.sidebarHeader}>
          <h2 className={styles.sidebarTitle}>Agents</h2>
          <button
            className={styles.newBtn}
            onClick={() => { setEditAgent(null); setWelcomeExample(null); setShowCreate(true) }}
          >
            <Plus size={13} />
            New
          </button>
        </div>

        <div className={styles.agentList}>
          {agents.length === 0 ? (
            <div className={styles.emptyList}>
              <div className={styles.emptyListIcon}><Brain size={22} color="#9ca3af" /></div>
              <p className={styles.emptyListTitle}>No agents yet</p>
              <p className={styles.emptyListSub}>Create your first agent to automate data insights.</p>
            </div>
          ) : (
            agents.map((agent) => {
              const agentRuns    = getRuns(agent.id)
              const lastRun      = agentRuns[agentRuns.length - 1]
              const agentRunning = runningIds.has(agent.id)

              return (
                <div
                  key={agent.id}
                  className={`${styles.agentCard} ${selectedId === agent.id ? styles.agentCardActive : ''}`}
                  onClick={() => { setSelectedId(agent.id); setDetailTab('runs') }}
                >
                  <div className={styles.agentCardHeader}>
                    <div className={`${styles.agentIcon} ${!agent.enabled ? styles.agentIconDisabled : ''}`}>
                      <Brain size={14} color="#fff" />
                    </div>
                    <span className={`${styles.agentCardName} ${!agent.enabled ? styles.agentCardNameDisabled : ''}`}>
                      {agent.name}
                    </span>
                    {agentRunning && <span className={styles.agentCardRunningDot} />}
                  </div>

                  {agent.description && (
                    <p className={styles.agentCardDesc}>{agent.description}</p>
                  )}

                  <div className={styles.agentCardMeta}>
                    {agent.deployed ? (
                      <span className={styles.deployedBadge}>
                        <Rocket size={9} />
                        {scheduleLabel(agent.schedule, agent.scheduleTime)}
                      </span>
                    ) : (
                      <span className={styles.scheduleBadge}>
                        <Clock size={10} />
                        Draft
                      </span>
                    )}
                    <span className={`${styles.statusDot} ${agent.deployed ? styles.statusDotDeployed : agent.enabled ? styles.statusDotEnabled : ''}`} />
                    {agentRunning && (
                      <span className={styles.lastRun} style={{ color: '#3b82f6' }}>Working…</span>
                    )}
                    {!agentRunning && lastRun && (
                      <span className={styles.lastRun}>
                        {lastRun.status === 'failed' ? '✗ ' : ''}
                        {formatRelative(lastRun.startedAt)}
                      </span>
                    )}
                  </div>
                </div>
              )
            })
          )}
        </div>
      </div>

      {/* ── Main panel ──────────────────────────────────────────────────────── */}
      <div className={styles.main}>
        {!selectedAgent ? (
          <div className={styles.welcome}>
            <div className={styles.welcomeIcon}><Brain size={30} color="#9ca3af" /></div>
            <h1 className={styles.welcomeTitle}>Build intelligent agents</h1>
            <p className={styles.welcomeSubtitle}>
              Task agents to automatically mine your data, detect patterns, and surface insights — on a schedule or on demand.
            </p>
            <div className={styles.welcomeExamples}>
              {EXAMPLE_AGENTS.map((ex, i) => (
                <button key={i} className={styles.welcomeExampleCard} onClick={() => handleWelcomeExample(ex)}>
                  <div className={styles.welcomeExampleTitle}>{ex.name}</div>
                  <div className={styles.welcomeExampleDesc}>{ex.desc}</div>
                </button>
              ))}
            </div>
          </div>
        ) : (
          <>
            {/* ── Detail header ─────────────────────────────────────────────── */}
            <div className={styles.detailHeader}>
              <div className={styles.detailHeaderTop}>
                <div className={`${styles.detailIcon} ${!selectedAgent.enabled ? styles.detailIconDisabled : ''} ${isRunning ? styles.detailIconRunning : ''}`}>
                  <Brain size={20} color="#fff" />
                </div>

                <div className={styles.detailMeta}>
                  <h2 className={styles.detailName}>{selectedAgent.name}</h2>
                  {isRunning ? (
                    <p className={styles.detailRunningLabel}>
                      <span className={styles.pulsingDot} />
                      Working in the background…
                    </p>
                  ) : (
                    selectedAgent.description && (
                      <p className={styles.detailDesc}>{selectedAgent.description}</p>
                    )
                  )}
                </div>

                <div className={styles.detailActions}>
                  <button
                    className={styles.actionBtn}
                    onClick={() => { setEditAgent(selectedAgent); setShowCreate(true) }}
                    disabled={isRunning}
                  >
                    <Pencil size={13} />
                    Edit
                  </button>

                  {isDeployed ? (
                    <button
                      className={`${styles.actionBtn} ${styles.actionBtnDeployed}`}
                      onClick={() => handleUndeploy(selectedAgent.id)}
                      disabled={isRunning}
                      title="Agent is deployed and scheduled. Click to undeploy."
                    >
                      <CheckCheck size={13} />
                      Deployed
                    </button>
                  ) : (
                    <button
                      className={`${styles.actionBtn} ${styles.actionBtnDeploy} ${isRunning ? styles.actionBtnRunning : ''}`}
                      onClick={() => handleDeploy(selectedAgent)}
                      disabled={isRunning || !selectedAgent.connectionId}
                      title="Deploy agent — enables scheduling and locks output format"
                    >
                      {isRunning ? (
                        <><span className={styles.runningSpinner} /> Working…</>
                      ) : (
                        <><Rocket size={13} /> Deploy</>
                      )}
                    </button>
                  )}

                  <button
                    className={`${styles.actionBtn} ${styles.actionBtnDanger}`}
                    onClick={() => handleDelete(selectedAgent.id)}
                    disabled={isRunning}
                    title="Delete agent"
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              </div>

              <div className={styles.detailTabs}>
                <button
                  className={`${styles.detailTab} ${detailTab === 'runs' ? styles.detailTabActive : ''}`}
                  onClick={() => setDetailTab('runs')}
                >
                  Conversation
                </button>
                <button
                  className={`${styles.detailTab} ${detailTab === 'config' ? styles.detailTabActive : ''}`}
                  onClick={() => setDetailTab('config')}
                >
                  Configuration
                </button>

                {/* Deployment status pill */}
                {isDeployed ? (
                  <span className={styles.deployedPill}>
                    <span className={styles.deployedDot} />
                    Deployed
                    {nextRun && (
                      <span className={styles.nextRunLabel}>
                        · next {nextRun.toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' })}
                      </span>
                    )}
                  </span>
                ) : (
                  <span className={styles.draftPill}>Draft — not scheduled</span>
                )}
              </div>
            </div>

            {/* ── Conversation tab ─────────────────────────────────────────── */}
            {detailTab === 'runs' && (
              <div className={styles.conversationWrap}>
                <div className={styles.conversationScroll} ref={scrollRef}>
                  {!hasRuns && !isRunning ? (
                    <div className={styles.runsEmpty}>
                      <div className={styles.runsEmptyIcon}>
                        <FlaskConical size={22} color="#9ca3af" />
                      </div>
                      <p className={styles.runsEmptyText}>
                        Test your agent to see what it produces,<br />
                        then <strong>Deploy</strong> to put it on a schedule.
                      </p>
                      <button
                        className={styles.testNowBtn}
                        onClick={() => handleTest(selectedAgent)}
                        disabled={isRunning || !selectedAgent.connectionId}
                        title="Run a test — results stay in conversation until you're satisfied"
                      >
                        <Play size={13} />
                        Test now
                      </button>
                    </div>
                  ) : (
                    <>
                      {runs.map((run) => (
                        <ConversationTurn key={run.id} run={run} />
                      ))}

                      {/* Working indicator — shown while last run is still in-flight */}
                      {isRunning && (
                        <WorkingIndicator connectionName={selectedConnection?.connectionName} />
                      )}
                    </>
                  )}
                </div>

                {/* Reply input */}
                <div className={`${styles.replyArea} ${isRunning ? styles.replyAreaLocked : ''}`}>
                  {isRunning ? (
                    /* Frozen state — clearly shows agent is busy */
                    <div className={styles.replyLocked}>
                      <Lock size={13} color="#9ca3af" />
                      <span className={styles.replyLockedText}>
                        Agent is working — input available once analysis is complete
                      </span>
                    </div>
                  ) : (
                    <>
                      {hasRuns && (
                        <div className={styles.replyHint}>
                          Replying to <strong>{selectedAgent.name}</strong>
                          {selectedConnection && <span> · {selectedConnection.connectionName}</span>}
                          <button className={styles.clearHistoryBtn} onClick={handleClearHistory} title="Clear conversation">
                            <Eraser size={11} />
                            Clear
                          </button>
                        </div>
                      )}

                      <div className={styles.replyWrap}>
                        <textarea
                          ref={replyRef}
                          className={styles.replyInput}
                          placeholder={
                            hasRuns
                              ? 'Reply to the agent — clarify, refine, or ask follow-up questions…'
                              : 'Ask something, or hit Test now to run the agent task…'
                          }
                          value={reply}
                          onChange={(e) => { setReply(e.target.value); autoResize() }}
                          onKeyDown={handleReplyKeyDown}
                          rows={1}
                        />
                        <div className={styles.replyBtns}>
                          {!reply.trim() && (
                            <button
                              className={styles.replyTestBtn}
                              onClick={() => handleTest(selectedAgent)}
                              disabled={isRunning || !selectedAgent.connectionId}
                              title="Run agent task as a test"
                            >
                              <Play size={12} />
                              Test
                            </button>
                          )}
                          <button
                            className={styles.replySendBtn}
                            onClick={handleReply}
                            disabled={!reply.trim() || !selectedAgent.connectionId}
                            title="Send (Enter)"
                          >
                            <ArrowUp size={14} />
                          </button>
                        </div>
                      </div>
                      <p className={styles.replyFooter}>
                        Enter to send · Shift+Enter for new line
                      </p>
                    </>
                  )}
                </div>
              </div>
            )}

            {/* ── Config tab ──────────────────────────────────────────────── */}
            {detailTab === 'config' && (
              <div className={styles.detailBody}>
                <div className={styles.configSection}>
                  <div className={styles.configLabel}>Task instructions</div>
                  <div className={styles.configValue}>{selectedAgent.task}</div>
                </div>

                <div className={styles.configRow}>
                  <div className={styles.configSection}>
                    <div className={styles.configLabel}>Database connection</div>
                    <div className={styles.configValue}>
                      {selectedConnection
                        ? `${selectedConnection.connectionName} (${selectedConnection.dbType})`
                        : selectedAgent.connectionId || '—'}
                    </div>
                  </div>
                  <div className={styles.configSection}>
                    <div className={styles.configLabel}>Schedule</div>
                    <div className={styles.configValue}>
                      {scheduleLabel(selectedAgent.schedule, selectedAgent.scheduleTime)}
                    </div>
                  </div>
                </div>

                <div className={styles.configRow}>
                  <div className={styles.configSection}>
                    <div className={styles.configLabel}>Deployment</div>
                    <div className={styles.configValue}>
                      {selectedAgent.deployed
                        ? `Deployed ${selectedAgent.deployedAt ? new Date(selectedAgent.deployedAt).toLocaleString() : ''}`
                        : 'Draft — not yet deployed'}
                    </div>
                  </div>
                  <div className={styles.configSection}>
                    <div className={styles.configLabel}>Next scheduled run</div>
                    <div className={styles.configValue}>
                      {nextRun ? nextRun.toLocaleString() : '—'}
                    </div>
                  </div>
                </div>

                <div className={styles.configRow}>
                  <div className={styles.configSection}>
                    <div className={styles.configLabel}>Created</div>
                    <div className={styles.configValue}>
                      {selectedAgent.createdAt ? new Date(selectedAgent.createdAt).toLocaleString() : '—'}
                    </div>
                  </div>
                  <div className={styles.configSection}>
                    <div className={styles.configLabel}>Output snapshot locked</div>
                    <div className={styles.configValue}>
                      {selectedAgent.deploymentSnapshot ? 'Yes — consistent format enforced' : 'No snapshot yet'}
                    </div>
                  </div>
                </div>

                {selectedAgent.updatedAt && (
                  <div className={styles.configSection}>
                    <div className={styles.configLabel}>Last updated</div>
                    <div className={styles.configValue}>
                      {new Date(selectedAgent.updatedAt).toLocaleString()}
                    </div>
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </div>

      {/* ── Modals ──────────────────────────────────────────────────────────── */}
      {showCreate && (
        <CreateAgentModal
          connections={connections}
          existingAgent={editAgent}
          initialValues={welcomeExample}
          onSave={handleSaveAgent}
          onClose={() => { setShowCreate(false); setEditAgent(null); setWelcomeExample(null) }}
        />
      )}
    </div>
  )
}
