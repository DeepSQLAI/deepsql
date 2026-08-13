import { useState, useEffect, useRef, useCallback } from 'react'
import { LineChart, ArrowUp, ChevronLeft, Sparkles, Check, Loader2, Brain, PencilRuler, ClipboardCheck, TrendingUp, Users, PieChart, Layers } from 'lucide-react'
import DashboardArtifact from '@/components/DashboardArtifact'
import ShareMenu from './ShareMenu'
import { savedDashboardsAPI } from '@/lib/api/client'
import { generateDashboardStream } from '@/lib/dashboardGenerator'
import styles from './DashboardWorkspace.module.css'

// Icon per agent step phase, so the live trace reads at a glance.
const STEP_ICON = { grounding: Brain, planning: PencilRuler, validating: ClipboardCheck, done: Check }

// Starting points shown on a brand-new, empty dashboard — concrete enough to click
// and send immediately, so the first screen a user sees isn't just an empty prompt.
const EXAMPLE_PROMPTS = [
  { icon: TrendingUp, title: 'Revenue by month', prompt: 'Show revenue by month for the last 12 months, with a trend line and month-over-month change.' },
  { icon: Users, title: 'Top customers', prompt: 'Show top customers by total spend, with their order count and average order value.' },
  { icon: PieChart, title: 'Order breakdown', prompt: 'Show order status breakdown (completed, pending, cancelled) as a share of total orders.' },
  { icon: Layers, title: 'Business overview', prompt: 'Build a dashboard with the most important KPIs and a couple of charts summarizing overall business health.' },
]

// Focused, chrome-less builder. Left = the agent (generate / refine); main = the
// live dashboard canvas. The DeepSQL logo and breadcrumb return to the gallery.
export default function DashboardWorkspace({ connectionId, dashboard, onClose }) {
  const isNew = !dashboard
  const [savedId, setSavedId] = useState(dashboard?.id || null)
  const [config, setConfig] = useState(null)
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [thinking, setThinking] = useState(false)
  const [steps, setSteps] = useState([])
  const [elapsed, setElapsed] = useState(0)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [isPublic, setIsPublic] = useState(dashboard?.isPublic || false)
  const scrollRef = useRef(null)
  const abortRef = useRef(null)
  const inputRef = useRef(null)
  const chatSyncedRef = useRef(false) // skips the redundant persist right after restore
  const savedIdRef = useRef(dashboard?.id || null) // latest id for async callbacks (avoids stale closure double-create)
  const messagesRef = useRef([])

  // Cancel any in-flight generation on unmount.
  useEffect(() => () => { if (abortRef.current) abortRef.current() }, [])

  // Auto-grow the composer with its content (up to a max), like a chat box.
  useEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 140)}px`
  }, [input])

  // Elapsed-time ticker so a 15-30s build reads as alive, not stuck.
  useEffect(() => {
    if (!thinking) { setElapsed(0); return undefined }
    const t0 = Date.now()
    const id = setInterval(() => setElapsed(Math.floor((Date.now() - t0) / 1000)), 1000)
    return () => clearInterval(id)
  }, [thinking])

  useEffect(() => {
    if (isNew) {
      setMessages([{ role: 'agent', text: 'Tell me what to chart and I’ll build it — grounded on your schema and business rules. Try “revenue by month” or “top customers by spend”.' }])
      return
    }
    let cfg = {}
    try { cfg = typeof dashboard.dashboardConfig === 'string' ? JSON.parse(dashboard.dashboardConfig || '{}') : (dashboard.dashboardConfig || {}) } catch { cfg = {} }
    // A saved row can carry a chat-only reply object (from an in-flight chat turn
    // that was never a real build) instead of an artifact — rendering that as-is
    // would silently show the pristine empty canvas with no explanation, right next
    // to chat history that says "Done — built". Treat it as no build yet instead.
    setConfig(cfg.html ? { ...cfg, updatedAt: dashboard.updatedAt || new Date().toISOString() } : null)
    chatSyncedRef.current = false
    // Restore the persisted per-dashboard chat thread if there is one, so the
    // build/edit conversation survives a refresh; otherwise open with a greeting.
    let saved = null
    try { saved = dashboard.chatMessages ? JSON.parse(dashboard.chatMessages) : null } catch { saved = null }
    setMessages(Array.isArray(saved) && saved.length
      ? saved.map((m) => ({ ...m, streaming: false }))
      : [{ role: 'agent', text: `Here’s “${dashboard.name || 'your dashboard'}”. Ask me to add a chart, change a metric, or filter — the canvas updates live.` }])
  }, [dashboard, isNew])

  useEffect(() => { if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight }, [messages, thinking])

  // Persist the chat thread per dashboard as it settles (once the dashboard is
  // saved) so the conversation survives a refresh without re-clicking Save. Sends
  // only chatMessages, so it never overwrites the saved dashboard HTML. Skips the
  // first settled state after (re)opening — those messages were just restored.
  useEffect(() => {
    if (!savedId || thinking || !messages.length) return
    if (!chatSyncedRef.current) { chatSyncedRef.current = true; return }
    savedDashboardsAPI.updateDashboard(savedId, { chatMessages: JSON.stringify(messages) }).catch(() => {})
  }, [messages, thinking, savedId])

  const name = config?.title || (isNew ? 'New dashboard' : dashboard?.name) || 'Dashboard'
  // "Live" once published to the web; the ShareMenu keeps this in sync.
  const status = isPublic ? 'live' : 'draft'

  useEffect(() => { messagesRef.current = messages }, [messages])
  useEffect(() => { savedIdRef.current = savedId }, [savedId])

  // Persist the dashboard as a draft — create the row on first build, update it
  // after. Called automatically on every successful generation so a refresh mid-
  // creation never loses the dashboard, and by the explicit Save button.
  const persistDraft = useCallback(async (cfg, msgs) => {
    if (!cfg) return false
    const body = {
      connectionId,
      name: cfg.title || 'Untitled dashboard',
      description: cfg.description || '',
      dashboardConfig: JSON.stringify(cfg),
      chatMessages: JSON.stringify(msgs || messagesRef.current),
      isFavorite: false,
    }
    try {
      if (savedIdRef.current) {
        await savedDashboardsAPI.updateDashboard(savedIdRef.current, body)
      } else {
        const res = await savedDashboardsAPI.createDashboard(body)
        const created = res?.savedDashboard || res?.dashboard || res
        if (created?.id) { savedIdRef.current = created.id; setSavedId(created.id) }
      }
      chatSyncedRef.current = true
      setDirty(false)
      return true
    } catch (e) {
      setDirty(true) // keep the Save button available to retry
      throw e
    }
  }, [connectionId])

  function submit(directPrompt) {
    const prompt = (directPrompt ?? input).trim()
    if (!prompt || thinking) return
    setInput('')
    setMessages((m) => [...m, { role: 'user', text: prompt }])
    setSteps([])
    setThinking(true)
    if (abortRef.current) abortRef.current()
    abortRef.current = generateDashboardStream(connectionId, prompt, config, {
      onStep: (s) => setSteps((prev) => [...prev, s]),
      onChat: (reply) => {
        // Just a reply — e.g. "hi" — not a dashboard change. No save, no config touch.
        abortRef.current = null
        setThinking(false)
        setSteps([])
        setMessages((m) => [...m, { role: 'agent', text: reply || '…' }])
      },
      onDone: (next) => {
        abortRef.current = null
        setThinking(false)
        setSteps([])
        // Belt-and-braces: a chat-shaped payload must never hit the "built" path
        // (that appends the canned save line and would clobber a real artifact).
        if (!next?.html || next?.chat) {
          setMessages((m) => [...m, { role: 'agent', text: next?.reply || '…' }])
          return
        }
        setConfig(next)
        // Auto-save as a draft so a refresh never loses it (create first time, update after).
        const updated = [...messagesRef.current, { role: 'agent', text: 'Done — built and verified against your data. Saved as a draft — tell me what to change.' }]
        setMessages(updated)
        persistDraft(next, updated).catch(() => {
          setMessages((m) => [...m, { role: 'agent', text: '⚠ Built, but couldn’t auto-save yet — click Save to keep it.', error: true }])
        })
      },
      onError: (e) => {
        abortRef.current = null
        setMessages((m) => [...m, { role: 'agent', text: `⚠ ${e?.message || 'Generation failed.'}`, error: true }])
        setThinking(false)
        setSteps([])
      },
    })
  }

  async function save() {
    if (!config || saving) return
    setSaving(true)
    try {
      await persistDraft(config, messages)
      setMessages((m) => [...m, { role: 'agent', text: 'Saved. Find it on the Dashboards home anytime.' }])
    } catch (e) {
      setMessages((m) => [...m, { role: 'agent', text: `⚠ Couldn’t save: ${e?.response?.data?.message || e?.message || 'error'}`, error: true }])
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={styles.root}>
      <header className={styles.topbar}>
        <button className={styles.logoBtn} onClick={onClose} title="Back to dashboards" aria-label="Back to dashboards">
          <span className={styles.logoMark}><LineChart size={15} color="#fff" /></span>
        </button>
        <button className={styles.crumbLink} onClick={onClose}>Dashboards</button>
        <span className={styles.sep}>/</span>
        <span className={styles.crumbCur}>{name}</span>
        <span className={styles.spacer} />
        <span className={status === 'live' ? styles.pillLive : styles.pillDraft}>{status === 'live' ? 'Live' : 'Draft'}</span>
        {config && (dirty || !savedId) && (
          <button className={styles.saveBtn} onClick={save} disabled={saving}>
            {saving ? <Loader2 size={14} className={styles.spin} /> : <Check size={14} />} Save
          </button>
        )}
        <ShareMenu
          savedId={savedId}
          initialPublic={dashboard?.isPublic}
          initialToken={dashboard?.shareToken}
          initialPasswordSet={dashboard?.sharePasswordSet}
          onPublicChange={setIsPublic}
        />
      </header>

      <div className={styles.body}>
        <aside className={styles.agent}>
          <div className={styles.agentScroll} ref={scrollRef}>
            {messages.map((msg, i) => (
              <div key={i} className={msg.role === 'user' ? styles.bubbleUser : (msg.error ? styles.bubbleErr : styles.bubbleAgent)}>
                {msg.text}
              </div>
            ))}
            {thinking && (
              <div className={styles.trace}>
                <div className={styles.traceHead}>
                  <span className={styles.traceHeadLabel}>
                    <span className={styles.traceDotOuter}><span className={styles.traceDotInner} /></span>
                    Working
                  </span>
                  <span className={styles.traceTime}>{elapsed}s</span>
                </div>
                {steps.length > 0 && (
                  <div className={styles.traceList}>
                    {steps.slice(-7).map((s, i, arr) => {
                      const Icon = STEP_ICON[s.type] || ClipboardCheck
                      const last = i === arr.length - 1
                      return (
                        <div key={steps.length - arr.length + i} className={styles.traceRow}>
                          <span className={styles.traceRail}>
                            <span className={last ? styles.traceIconActive : styles.traceIconDone}>
                              {last ? <Icon size={11} /> : <Check size={11} />}
                            </span>
                            {i < arr.length - 1 && <span className={styles.traceLine} />}
                          </span>
                          <span className={last ? styles.traceTextActive : styles.traceTextDone}>{s.message}</span>
                        </div>
                      )
                    })}
                  </div>
                )}
              </div>
            )}
          </div>
          <div className={styles.composer}>
            <textarea
              ref={inputRef}
              rows={1}
              className={styles.composerInput}
              placeholder={thinking ? 'Building… (you can queue the next change)' : (isNew && !config ? 'Describe the dashboard you want…' : 'Add a chart, change a metric…')}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); if (!thinking) submit() } }}
              autoFocus
            />
            <button className={styles.sendBtn} onClick={() => submit()} disabled={thinking || !input.trim()} aria-label="Send"><ArrowUp size={16} /></button>
          </div>
        </aside>

        <main className={styles.canvas}>
          {config?.html ? (
            <DashboardArtifact connectionId={connectionId} html={config.html} onError={(msg) => console.warn('Dashboard artifact error:', msg)} />
          ) : (
            <div className={styles.newCanvas}>
              <div className={styles.newCanvasInner}>
                <span className={styles.newIcon}><Sparkles size={22} color="#534AB7" /></span>
                <h2 className={styles.newTitle}>{isNew ? 'Build a dashboard' : 'Nothing built here yet'}</h2>
                <p className={styles.newSub}>
                  {isNew
                    ? 'Describe what you want on the left and the DeepSQL agent builds it here — read-only, grounded on your data.'
                    : 'This dashboard doesn’t have a build yet — the chat on the left may just be planning so far. Ask for a chart to get started.'}
                </p>

                <div className={styles.exampleGrid}>
                  {EXAMPLE_PROMPTS.map(({ icon: Icon, title, prompt }) => (
                    <button
                      key={title}
                      className={styles.exampleCard}
                      onClick={() => submit(prompt)}
                      disabled={thinking}
                    >
                      <span className={styles.exampleIcon}><Icon size={16} /></span>
                      <span className={styles.exampleTitle}>{title}</span>
                      <span className={styles.examplePrompt}>{prompt}</span>
                    </button>
                  ))}
                </div>

                <button className={styles.backLink} onClick={onClose}><ChevronLeft size={14} /> Back to dashboards</button>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
