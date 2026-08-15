import { useState, useEffect, useRef, useCallback, lazy, Suspense } from 'react'
import { LineChart, ArrowUp, ChevronLeft, Sparkles, Check, Loader2, Brain, PencilRuler, ClipboardCheck, TrendingUp, Users, PieChart, Layers, Code2, X, Copy, Undo2, Play, Database } from 'lucide-react'
import DashboardArtifact from '@/components/DashboardArtifact'
import ShareMenu from './ShareMenu'
import { useDashboardChatStore, useDashboardSession, useDashboardChatActions } from '@/lib/stores/useDashboardChatStore'
import styles from './DashboardWorkspace.module.css'

const Editor = lazy(() => import('@monaco-editor/react'))

// Icon per agent step phase, so the live trace reads at a glance.
const STEP_ICON = { grounding: Brain, planning: PencilRuler, sql: Database, validating: ClipboardCheck, done: Check }

// Mirror of DashboardAgentService.extractTitle — a dashboard's name comes from
// the artifact's <title> (then its <h1>). The backend only derives it while
// generating, so a hand-edited source has to re-derive it here: without this,
// editing <title> in the Source tab saves the new HTML but leaves the dashboard
// listed and breadcrumbed under its old name, which reads as "my edit didn't
// save" even though it did.
function titleFromHtml(html, fallback) {
  const strip = (s) => s.replace(/[<>]/g, '').replace(/\s+/g, ' ').trim()
  const title = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)
  if (title && strip(title[1])) return strip(title[1]).slice(0, 120)
  const h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i)
  if (h1 && strip(h1[1])) return strip(h1[1]).slice(0, 120)
  return fallback
}

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
//
// Generation state (chat, streaming steps, the built config) lives in
// useDashboardChatStore, not component state — so navigating away from this
// screen mid-build no longer aborts the agent turn or drops the chat. This
// component is a thin subscriber to a "session" keyed by dashboard id (or
// `new:<connectionId>` before the first save); see the store for the key/alias
// scheme that lets a not-yet-saved dashboard survive being rekeyed to a real id.
export default function DashboardWorkspace({ connectionId, dashboard, onClose }) {
  const isNew = !dashboard
  const keyRef = useRef(dashboard?.id ? String(dashboard.id) : `new:${connectionId}`)
  const key = keyRef.current

  const session = useDashboardSession(key)
  const { ensureSession, patchSession, releaseAlias, submitPrompt, resumeIfRunning, persistDraft } = useDashboardChatActions()
  const { messages, thinking, steps, startedAt, config, savedId, dirty, liveShell, liveWidget, renderHtml } = session

  const [input, setInput] = useState('')
  const [elapsed, setElapsed] = useState(0)
  const [saving, setSaving] = useState(false)
  const [isPublic, setIsPublic] = useState(dashboard?.isPublic || false)
  const [viewMode, setViewMode] = useState('preview') // 'preview' | 'source'
  const [sourceDraft, setSourceDraft] = useState('')
  const [sourceEpoch, setSourceEpoch] = useState(0) // bump = remount the editor with fresh content
  const sourceBaseRef = useRef(null) // html the editor was last seeded with
  const [showQueries, setShowQueries] = useState(false)
  const [queries, setQueries] = useState([])
  const scrollRef = useRef(null)
  const inputRef = useRef(null)
  const artifactRef = useRef(null)
  const mountedWidgetSeqRef = useRef(0)

  // Progressive build: as soon as the shell chunk lands, show it as the live
  // canvas (a build in progress has its own in-flight artifact, distinct from
  // `config` — the last COMPLETED build — so this never clobbers or races it).
  // Each widget chunk afterward is a one-shot mount into the already-loaded
  // iframe; `seq` (not the widget id) gates the effect so the SAME widget id
  // reappearing later (a self-review correction) still re-mounts.
  const liveConfig = liveShell ? { renderMode: 'artifact', html: liveShell } : null
  useEffect(() => {
    if (!liveWidget || liveWidget.seq === mountedWidgetSeqRef.current) return
    mountedWidgetSeqRef.current = liveWidget.seq
    artifactRef.current?.mountWidget(liveWidget.id, liveWidget.html)
  }, [liveWidget])

  // Seed this session exactly once per mount — restoring the persisted chat/
  // config for an existing dashboard, or a greeting for a new one. Never
  // touches an already-running/already-resumed session (ensureSession no-ops
  // if one exists). For a `new:` key whose prior occupant already got saved
  // and rekeyed away (a stale alias with no raw session left), release it
  // first so this genuinely new dashboard doesn't inherit that chat.
  useEffect(() => {
    if (isNew) {
      const state = useDashboardChatStore.getState()
      const hasRawSession = Object.prototype.hasOwnProperty.call(state.sessions, key)
      if (!hasRawSession && state.aliases[key]) releaseAlias(key)
      ensureSession(key, {
        messages: [{ role: 'agent', text: 'Tell me what to chart and I’ll build it — grounded on your schema and business rules. Try “revenue by month” or “top customers by spend”.' }],
      })
      return
    }
    let cfg = {}
    try { cfg = typeof dashboard.dashboardConfig === 'string' ? JSON.parse(dashboard.dashboardConfig || '{}') : (dashboard.dashboardConfig || {}) } catch { cfg = {} }
    let saved = null
    try { saved = dashboard.chatMessages ? JSON.parse(dashboard.chatMessages) : null } catch { saved = null }
    // Only a genuinely fresh mount (no in-memory session yet — e.g. the tab was
    // closed/reloaded, or this is the first time opening this dashboard) should
    // try to resume a still-running generation below. A session that already
    // exists (resumed via in-app navigation) already has the live answer, live
    // steps, or is already polling — resuming again would restart a poll loop
    // needlessly or clobber a result this tab already has.
    const alreadyHadSession = !!useDashboardChatStore.getState().sessions[useDashboardChatStore.getState().resolveKey(key)]
    ensureSession(key, {
      // A saved row can carry a chat-only reply object (from an in-flight chat
      // turn that was never a real build) instead of an artifact — rendering
      // that as-is would silently show the pristine empty canvas with no
      // explanation. Treat it as no build yet instead.
      config: cfg.html ? { ...cfg, updatedAt: dashboard.updatedAt || new Date().toISOString() } : null,
      savedId: dashboard.id ? String(dashboard.id) : null,
      messages: Array.isArray(saved) && saved.length
        ? saved.map((m) => ({ ...m, streaming: false }))
        : [{ role: 'agent', text: `Here’s “${dashboard.name || 'your dashboard'}”. Ask me to add a chart, change a metric, or filter — the canvas updates live.` }],
    })
    // The backend persists each turn itself now (see useDashboardChatStore's
    // header comment) — if it's still RUNNING, a turn was in flight when this
    // tab wasn't around to receive it live. Poll until it resolves instead of
    // assuming nothing is happening.
    if (!alreadyHadSession && dashboard.id) {
      resumeIfRunning(key, String(dashboard.id), dashboard.generationStatus, dashboard.generationStartedAt)
    }
    // key/dashboard/isNew are stable for this mount — DashboardsSection always
    // fully unmounts/remounts on a different dashboard or connection.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Auto-grow the composer with its content (up to a max), like a chat box.
  useEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 140)}px`
  }, [input])

  // Elapsed-time ticker so a 15-30s build reads as alive, not stuck. Reads the
  // session's absolute startedAt, so re-opening a still-running build shows
  // correctly continued elapsed time rather than restarting from 0.
  useEffect(() => {
    if (!thinking || !startedAt) { setElapsed(0); return undefined }
    const tick = () => setElapsed(Math.floor((Date.now() - startedAt) / 1000))
    tick()
    const id = setInterval(tick, 1000)
    return () => clearInterval(id)
  }, [thinking, startedAt])

  useEffect(() => { if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight }, [messages, thinking])

  // Seed the Source editor when the artifact changes from OUTSIDE the editor —
  // a fresh agent build, or opening a saved dashboard. The editor itself is
  // deliberately UNCONTROLLED (defaultValue + key, never value): a controlled
  // `value` rewrites Monaco's model on every keystroke, which resets the caret
  // mid-word, so characters land at the wrong offset or vanish (editing
  // `<title>Business Overview` produced `<title>Business s` and a stray `<` at
  // offset 0). SqlRunnerTab.js avoids controlled value for the same reason.
  // Skipping our own Apply keeps scroll/caret intact while iterating.
  useEffect(() => {
    const html = config?.html || ''
    if (html === sourceBaseRef.current) return
    sourceBaseRef.current = html
    setSourceDraft(html)
    setSourceEpoch((e) => e + 1)
  }, [config?.html])
  // Query log is per-artifact. Reset the instant a NEW build/edit starts
  // streaming (liveShell's rising edge) rather than waiting for config.html to
  // change at the end — a live build already runs each widget's query well
  // before onDone, so waiting for completion left the old dashboard's queries
  // visible (and mixed with the new ones) for the whole build. Do NOT also
  // reset on config.html changing: once a build finishes without a reload
  // (nothing for self-review to correct — see onDone in useDashboardChatStore),
  // config.html changes from null/old to the final string with no new queries
  // to show, and resetting again here would wrongly wipe the ones just logged.
  useEffect(() => { if (liveShell) setQueries([]) }, [liveShell])

  const name = config?.title || (isNew ? 'New dashboard' : dashboard?.name) || 'Dashboard'
  // "Live" once published to the web; the ShareMenu keeps this in sync.
  const status = isPublic ? 'live' : 'draft'
  const sourceDirty = sourceDraft !== (config?.html || '')

  function submit(directPrompt) {
    const prompt = (directPrompt ?? input).trim()
    if (!prompt || thinking) return
    setInput('')
    submitPrompt(key, connectionId, prompt)
  }

  async function save() {
    if (!config || saving) return
    setSaving(true)
    try {
      await persistDraft(key, connectionId, config, messages)
      patchSession(key, (cur) => ({ messages: [...cur.messages, { role: 'agent', text: 'Saved. Find it on the Dashboards home anytime.' }] }))
    } catch (e) {
      patchSession(key, (cur) => ({ messages: [...cur.messages, { role: 'agent', text: `⚠ Couldn’t save: ${e?.response?.data?.message || e?.message || 'error'}`, error: true }] }))
    } finally {
      setSaving(false)
    }
  }

  function applySource() {
    // Editing source while the agent is mid-build would race two independent
    // writers of the same dashboardConfig — the backend's own completeBuildTurn
    // and this Apply — with no ordering guarantee over which lands last.
    if (!sourceDirty || !config || thinking) return
    sourceBaseRef.current = sourceDraft // our own change — don't remount the editor under the caret
    const next = {
      ...config,
      title: titleFromHtml(sourceDraft, config.title),
      html: sourceDraft,
      updatedAt: new Date().toISOString(),
    }
    patchSession(key, (cur) => ({
      config: next,
      messages: [...cur.messages, { role: 'agent', text: 'Source edited manually. Saved as a draft — tell me what to change next, or keep editing the source.' }],
    }))
    persistDraft(key, connectionId, next).catch(() => {
      patchSession(key, (cur) => ({ messages: [...cur.messages, { role: 'agent', text: '⚠ Edited, but couldn’t auto-save yet — click Save to keep it.', error: true }] }))
    })
  }

  function revertSource() {
    const html = config?.html || ''
    sourceBaseRef.current = html
    setSourceDraft(html)
    setSourceEpoch((e) => e + 1) // remount so the editor actually shows the restored text
  }

  const logQuery = useCallback((entry) => { setQueries((prev) => [...prev, entry]) }, [])
  const copyQuery = (sql) => { navigator.clipboard?.writeText(sql).catch(() => {}) }

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
          {config?.html || liveConfig ? (
            <>
              <div className={styles.canvasToolbar}>
                <div className={styles.canvasToolbarLeft}>
                  <div className={styles.viewToggle}>
                    {/* Editing source mid-build would be edited out from under the
                        user the moment the next chunk lands — Source only makes
                        sense once there's a finished, stable artifact. */}
                    <button className={viewMode === 'source' ? styles.viewToggleBtnActive : styles.viewToggleBtn} onClick={() => setViewMode('source')} disabled={!config?.html} title={!config?.html ? 'Available once the build finishes' : undefined}>Source</button>
                    <button className={viewMode === 'preview' ? styles.viewToggleBtnActive : styles.viewToggleBtn} onClick={() => setViewMode('preview')}>Preview</button>
                  </div>
                  {/* Only a real build in progress ever has a liveShell — a chat-only
                      reply (e.g. "hi") never emits one, so this never shows for that. */}
                  {liveConfig && !config?.html && (
                    <span className={styles.buildingBadge}>
                      <span className={styles.buildingDot} />
                      Building dashboard…
                    </span>
                  )}
                </div>
                <div className={styles.canvasToolbarRight}>
                  {viewMode === 'source' && (
                    <>
                      <button className={styles.ghostBtn} onClick={revertSource} disabled={!sourceDirty}><Undo2 size={13} /> Revert</button>
                      <button className={styles.primaryBtnSm} onClick={applySource} disabled={!sourceDirty || thinking} title={thinking ? 'Wait for the current build to finish first' : undefined}><Play size={13} /> Apply</button>
                    </>
                  )}
                  <button
                    className={showQueries ? styles.queriesBtnActive : styles.queriesBtn}
                    onClick={() => setShowQueries((v) => !v)}
                  >
                    <Code2 size={13} /> Queries{queries.length > 0 ? ` ${queries.length}` : ''}
                  </button>
                </div>
              </div>

              <div className={styles.canvasBody}>
                <div className={styles.canvasMain} style={{ display: viewMode === 'preview' || !config?.html ? 'block' : 'none' }}>
                  <DashboardArtifact
                    ref={artifactRef}
                    connectionId={connectionId}
                    html={liveConfig ? liveConfig.html : (renderHtml || config.html)}
                    onError={(msg) => console.warn('Dashboard artifact error:', msg)}
                    onQuery={logQuery}
                  />
                </div>
                {viewMode === 'source' && config?.html && (
                  <div className={styles.sourceEditor}>
                    <Suspense fallback={<div className={styles.editorLoading}>Loading editor…</div>}>
                      <Editor
                        key={sourceEpoch}
                        height="100%"
                        defaultLanguage="html"
                        defaultValue={sourceDraft}
                        onChange={(v) => setSourceDraft(v || '')}
                        theme="vs-light"
                        options={{
                          minimap: { enabled: false },
                          fontSize: 13,
                          lineNumbers: 'on',
                          roundedSelection: true,
                          scrollBeyondLastLine: false,
                          automaticLayout: true,
                          tabSize: 2,
                          wordWrap: 'on',
                        }}
                      />
                    </Suspense>
                  </div>
                )}

                {showQueries && (
                  <aside className={styles.queriesPanel}>
                    <div className={styles.queriesPanelHead}>
                      <span>Queries this dashboard runs</span>
                      <button onClick={() => setShowQueries(false)} aria-label="Close"><X size={14} /></button>
                    </div>
                    <div className={styles.queriesPanelList}>
                      {queries.length === 0 ? (
                        <div className={styles.queriesEmpty}>No queries run yet — they’ll show up as widgets load.</div>
                      ) : (
                        queries.map((q) => (
                          <div key={q.id} className={styles.queryCard}>
                            <div className={styles.queryCardHead}>
                              <span className={q.status === 'error' ? styles.queryBadgeErr : styles.queryBadgeOk}>
                                {q.status === 'error' ? 'Error' : `${q.rowCount} row${q.rowCount === 1 ? '' : 's'}`}
                              </span>
                              <span className={styles.queryTiming}>{q.durationMs} ms</span>
                              <button className={styles.queryCopyBtn} onClick={() => copyQuery(q.sql)} title="Copy SQL" aria-label="Copy SQL">
                                <Copy size={12} />
                              </button>
                            </div>
                            <pre className={styles.querySql}>{q.sql}</pre>
                            {q.status === 'error' && <div className={styles.queryErr}>{q.error}</div>}
                          </div>
                        ))
                      )}
                    </div>
                  </aside>
                )}
              </div>
            </>
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
