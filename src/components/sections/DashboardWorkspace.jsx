import { useState, useEffect, useRef, useCallback, lazy, Suspense } from 'react'
import { LineChart, ArrowUp, ChevronLeft, Sparkles, Check, Loader2, Brain, PencilRuler, Pencil, ClipboardCheck, TrendingUp, Users, PieChart, Layers, Code2, X, Copy, Undo2, Play, Database, History, RotateCcw, Eye, RefreshCw, ChevronDown, BellRing, Trash2 } from 'lucide-react'
import DashboardArtifact from '@/components/DashboardArtifact'
import ShareMenu from './ShareMenu'
import { savedDashboardsAPI } from '@/lib/api/client'
import { useDashboardChatStore, useDashboardSession, useDashboardChatActions } from '@/lib/stores/useDashboardChatStore'
import styles from './DashboardWorkspace.module.css'

const Editor = lazy(() => import('@monaco-editor/react'))

// Icon per agent step phase, so the live trace reads at a glance.
const STEP_ICON = { grounding: Brain, planning: PencilRuler, sql: Database, validating: ClipboardCheck, done: Check }

// Mirrors SavedDashboardService's snapshotVersion triggers, in plain language.
const VERSION_TRIGGER_LABEL = { AGENT_BUILD: 'Agent build', MANUAL_EDIT: 'Manual edit', RESTORE: 'Restore' }

const AUTO_REFRESH_OPTIONS = [
  { ms: 0, label: 'Off' },
  { ms: 30_000, label: '30s' },
  { ms: 5 * 60_000, label: '5m' },
  { ms: 60 * 60_000, label: '1h' },
]
const AUTO_REFRESH_LABEL = Object.fromEntries(AUTO_REFRESH_OPTIONS.map((o) => [o.ms, o.label]))

function versionRelTime(iso) {
  if (!iso) return ''
  const diff = Date.now() - new Date(iso).getTime()
  const m = Math.round(diff / 60000)
  if (m < 1) return 'just now'
  if (m < 60) return `${m}m ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h}h ago`
  return `${Math.round(h / 24)}d ago`
}

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

// A real line-diff between two agent-regenerated HTML docs is mostly noise — the agent
// rewrites large chunks even for small logical changes. Instead, summarize the shape of
// the change: title, widget count (via [data-widget] slots — see DashboardArtifact.jsx),
// and overall size, comparing this version against the one right after it (its "before").
function widgetIds(html) {
  const ids = new Set()
  const re = /data-widget=["']([^"']+)["']/g
  let m
  while ((m = re.exec(html))) ids.add(m[1])
  return ids
}

// A version's dashboardConfig is the same JSON shape as SavedDashboard.dashboardConfig
// (see DashboardVersion.dashboardConfig) — parse it the same way DashboardWorkspace
// already parses the live one, rather than trusting it's pre-parsed.
function versionHtml(version) {
  if (!version?.dashboardConfig) return null
  try {
    const cfg = JSON.parse(version.dashboardConfig)
    return cfg?.html || null
  } catch {
    return null
  }
}

function summarizeChange(fromHtml, toHtml) {
  if (!fromHtml || !toHtml) return null
  const fromTitle = titleFromHtml(fromHtml, '')
  const toTitle = titleFromHtml(toHtml, '')
  const fromWidgets = widgetIds(fromHtml)
  const toWidgets = widgetIds(toHtml)
  const added = [...toWidgets].filter((id) => !fromWidgets.has(id)).length
  const removed = [...fromWidgets].filter((id) => !toWidgets.has(id)).length

  const parts = []
  if (fromTitle && toTitle && fromTitle !== toTitle) parts.push(`title changed to “${toTitle}”`)
  if (added) parts.push(`+${added} widget${added === 1 ? '' : 's'}`)
  if (removed) parts.push(`-${removed} widget${removed === 1 ? '' : 's'}`)
  if (parts.length) return parts.join(', ')

  const sizeDelta = toHtml.length - fromHtml.length
  if (Math.abs(sizeDelta) < 20) return 'no visible change'
  return sizeDelta > 0 ? `content grew (+${sizeDelta.toLocaleString()} chars)` : `content shrank (${sizeDelta.toLocaleString()} chars)`
}

// Starting points shown on a brand-new, empty dashboard — concrete enough to click
// and send immediately, so the first screen a user sees isn't just an empty prompt.
const EXAMPLE_PROMPTS = [
  { icon: TrendingUp, title: 'Revenue by month', prompt: 'Show revenue by month for the last 12 months, with a trend line and month-over-month change.' },
  { icon: Users, title: 'Top customers', prompt: 'Show top customers by total spend, with their order count and average order value.' },
  { icon: PieChart, title: 'Order breakdown', prompt: 'Show order status breakdown (completed, pending, cancelled) as a share of total orders.' },
  { icon: Layers, title: 'Business overview', prompt: 'Build a dashboard with the most important KPIs and a couple of charts summarizing overall business health.' },
]

// Rotates under the real live step while a build is in flight — genuine,
// shippped-feature facts (not filler), so the wait teaches something instead
// of just decorating it. Kept short; the real step above stays the primary line.
const BUILD_TIPS = [
  'Every query is verified against your real data before it ever reaches the screen.',
  'Dashboards run read-only — the agent can look, never write.',
  'Ask to add a chart or change a metric any time — it edits in place.',
  'Every save keeps a version history, so you can always roll back a change.',
  'Publish a dashboard to get a read-only link — no login required to view it.',
  'Turn any dashboard into a TV/kiosk view that auto-refreshes on a wall display.',
  'Set a plain-English alert and DeepSQL checks it on a schedule for you.',
  'Duplicate a dashboard to branch it without touching the original.',
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
  const { ensureSession, patchSession, releaseAlias, submitPrompt, resumeIfRunning, persistDraft, renameDashboard } = useDashboardChatActions()
  const { messages, thinking, steps, startedAt, config, savedId, dirty, liveShell, liveWidget, renderHtml, titleOverride } = session

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
  const [showHistory, setShowHistory] = useState(false)
  const [versions, setVersions] = useState([])
  const [versionsLoading, setVersionsLoading] = useState(false)
  const [restoringId, setRestoringId] = useState(null)
  const [previewVersion, setPreviewVersion] = useState(null) // the version being previewed, or null
  const [autoRefreshMs, setAutoRefreshMs] = useState(0) // 0 = off
  const [showRefreshMenu, setShowRefreshMenu] = useState(false)
  const [lastRefreshedAt, setLastRefreshedAt] = useState(null)
  const refreshMenuRef = useRef(null)
  const [showAlerts, setShowAlerts] = useState(false)
  const [alerts, setAlerts] = useState([])
  const [alertsLoading, setAlertsLoading] = useState(false)
  const [newAlertText, setNewAlertText] = useState('')
  const [newAlertEmail, setNewAlertEmail] = useState('')
  const [savingAlert, setSavingAlert] = useState(false)
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
    el.style.height = `${Math.min(el.scrollHeight, 280)}px`
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

  // Rotates BUILD_TIPS while the main canvas shows the "Building…" state, so a
  // 15-30s wait has something changing on screen beyond the elapsed-time
  // ticker. Starts at a random index (not always the same first tip) and
  // resets once the build ends so the next one starts fresh.
  const [tipIndex, setTipIndex] = useState(() => Math.floor(Math.random() * BUILD_TIPS.length))
  useEffect(() => {
    if (!thinking) return undefined
    const id = setInterval(() => setTipIndex((i) => (i + 1) % BUILD_TIPS.length), 3500)
    return () => clearInterval(id)
  }, [thinking])

  const refreshNow = useCallback(() => {
    artifactRef.current?.reload()
    setLastRefreshedAt(Date.now())
  }, [])

  // Auto-refresh: re-run the artifact's queries on a fixed cadence while a build
  // isn't in flight — a build already replaces the whole iframe on completion, so
  // ticking during one would just race a reload no one asked for.
  useEffect(() => {
    if (!autoRefreshMs || thinking) return undefined
    const id = setInterval(refreshNow, autoRefreshMs)
    return () => clearInterval(id)
  }, [autoRefreshMs, thinking, refreshNow])

  useEffect(() => {
    if (!showRefreshMenu) return undefined
    const onDocClick = (e) => { if (refreshMenuRef.current && !refreshMenuRef.current.contains(e.target)) setShowRefreshMenu(false) }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [showRefreshMenu])

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

  // A user's explicit rename always wins — over both the HTML-derived title
  // and the saved row's name — until they rename again (see renameDashboard).
  const name = titleOverride || config?.title || (isNew ? 'Untitled dashboard' : dashboard?.name) || 'Untitled dashboard'
  // "Live" once published to the web; the ShareMenu keeps this in sync.
  const status = isPublic ? 'live' : 'draft'
  const sourceDirty = sourceDraft !== (config?.html || '')

  const [editingTitle, setEditingTitle] = useState(false)
  const [titleDraft, setTitleDraft] = useState(name)
  const titleInputRef = useRef(null)

  function startEditingTitle() {
    setTitleDraft(name)
    setEditingTitle(true)
  }
  function commitTitle() {
    setEditingTitle(false)
    const trimmed = titleDraft.trim()
    if (!trimmed || trimmed === name) return
    renameDashboard(key, trimmed)
  }

  useEffect(() => {
    if (editingTitle) { titleInputRef.current?.focus(); titleInputRef.current?.select() }
  }, [editingTitle])

  // v0.dev-style intro: a brand-new dashboard starts with the composer centered
  // and no side panel/canvas chrome at all. The instant the first user message
  // is sent, it docks into the left panel and the canvas takes over — driven by
  // whether a user message exists yet (not local state), so it resolves
  // correctly even if this component remounts mid-turn (e.g. reopening a
  // dashboard whose first turn already completed skips the intro entirely).
  const hasUserMessage = messages.some((m) => m.role === 'user')
  const [docking, setDocking] = useState(false)
  // Keep rendering the intro layout for one animation cycle after the message
  // that ends it — flipping the layout in the same commit as the message would
  // cut straight to the docked panel instead of gliding into it.
  const intro = isNew && (!hasUserMessage || docking)
  useEffect(() => {
    if (!docking) return undefined
    const id = setTimeout(() => setDocking(false), 420)
    return () => clearTimeout(id)
  }, [docking])

  function submit(directPrompt) {
    const prompt = (directPrompt ?? input).trim()
    if (!prompt || thinking) return
    if (isNew && !hasUserMessage) setDocking(true)
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
    setQueries([]) // the iframe is about to reload with the edited HTML — old widgets' queries no longer apply
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

  async function loadVersions() {
    if (!savedId) return
    setVersionsLoading(true)
    try {
      const res = await savedDashboardsAPI.getVersionHistory(savedId)
      setVersions(res?.versions || [])
    } catch {
      setVersions([])
    } finally {
      setVersionsLoading(false)
    }
  }

  function toggleHistory() {
    const next = !showHistory
    setShowHistory(next)
    if (next) loadVersions()
  }

  function selectAutoRefresh(ms) {
    setAutoRefreshMs(ms)
    setShowRefreshMenu(false)
  }

  async function loadAlerts() {
    if (!savedId) return
    setAlertsLoading(true)
    try {
      const res = await savedDashboardsAPI.getAlerts(savedId)
      setAlerts(res?.alerts || [])
    } catch {
      setAlerts([])
    } finally {
      setAlertsLoading(false)
    }
  }

  function toggleAlerts() {
    const next = !showAlerts
    setShowAlerts(next)
    if (next) loadAlerts()
  }

  async function createAlert() {
    if (!savedId || !newAlertText.trim() || savingAlert) return
    setSavingAlert(true)
    try {
      const recipients = newAlertEmail.trim()
      await savedDashboardsAPI.createAlert(savedId, {
        conditionText: newAlertText.trim(),
        channels: recipients ? 'in-app,email' : 'in-app',
        emailRecipients: recipients || null,
      })
      setNewAlertText('')
      setNewAlertEmail('')
      await loadAlerts()
    } catch (e) {
      patchSession(key, (cur) => ({ messages: [...cur.messages, { role: 'agent', text: `⚠ Couldn’t create alert: ${e?.response?.data?.message || e?.message || 'error'}`, error: true }] }))
    } finally {
      setSavingAlert(false)
    }
  }

  async function toggleAlertEnabled(alert) {
    try {
      await savedDashboardsAPI.updateAlert(savedId, alert.id, { isEnabled: !alert.isEnabled })
      await loadAlerts()
    } catch { /* leave state as-is on failure */ }
  }

  async function deleteAlert(alertId) {
    try {
      await savedDashboardsAPI.deleteAlert(savedId, alertId)
      setAlerts((prev) => prev.filter((a) => a.id !== alertId))
    } catch { /* leave state as-is on failure */ }
  }

  async function restoreVersion(version) {
    if (!savedId || restoringId || thinking) return
    setRestoringId(version.id)
    try {
      const res = await savedDashboardsAPI.restoreVersion(savedId, version.id)
      const restored = res?.savedDashboard
      if (!restored) return
      let cfg = {}
      try { cfg = typeof restored.dashboardConfig === 'string' ? JSON.parse(restored.dashboardConfig || '{}') : (restored.dashboardConfig || {}) } catch { cfg = {} }
      if (cfg.html) setQueries([]) // the iframe is about to reload with the restored HTML — old widgets' queries no longer apply
      patchSession(key, (cur) => ({
        config: cfg.html ? { ...cfg, updatedAt: restored.updatedAt || new Date().toISOString() } : cur.config,
        messages: [...cur.messages, { role: 'agent', text: `Restored “${version.name || 'a previous version'}”. The version it replaced was saved to history too.` }],
      }))
      await loadVersions()
    } catch (e) {
      patchSession(key, (cur) => ({ messages: [...cur.messages, { role: 'agent', text: `⚠ Couldn’t restore: ${e?.response?.data?.message || e?.message || 'error'}`, error: true }] }))
    } finally {
      setRestoringId(null)
    }
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
        {editingTitle ? (
          <input
            ref={titleInputRef}
            className={styles.crumbInput}
            value={titleDraft}
            onChange={(e) => setTitleDraft(e.target.value)}
            onBlur={commitTitle}
            onKeyDown={(e) => {
              if (e.key === 'Enter') { e.preventDefault(); commitTitle() }
              if (e.key === 'Escape') { e.preventDefault(); setEditingTitle(false) }
            }}
          />
        ) : (
          <button className={styles.crumbEditable} onClick={startEditingTitle} title="Rename dashboard">
            <span className={styles.crumbCur}>{name}</span>
            <Pencil size={12} className={styles.crumbEditIcon} />
          </button>
        )}
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

      <div className={intro ? styles.bodyIntro : styles.body}>
        <aside className={intro ? `${styles.agentIntro} ${docking ? styles.agentDocking : ''}` : styles.agent}>
          {intro && !docking && (
            <div className={styles.introHead}>
              <span className={styles.newIcon}><Sparkles size={22} color="#534AB7" /></span>
              <h2 className={styles.newTitle}>Build a dashboard</h2>
              <p className={styles.newSub}>Describe what you want and the DeepSQL agent builds it — read-only, grounded on your data.</p>
            </div>
          )}
          <div className={intro ? styles.agentScrollIntro : styles.agentScroll} ref={scrollRef}>
            {!intro && messages.map((msg, i) => (
              <div key={i} className={msg.role === 'user' ? styles.bubbleUser : (msg.error ? styles.bubbleErr : styles.bubbleAgent)}>
                {msg.text}
              </div>
            ))}
            {!intro && thinking && (
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
          {intro && !docking && (
            <div className={styles.exampleGridIntro}>
              {EXAMPLE_PROMPTS.map(({ icon: Icon, title, prompt }) => (
                <button key={title} className={styles.exampleCard} onClick={() => submit(prompt)} disabled={thinking}>
                  <span className={styles.exampleIcon}><Icon size={16} /></span>
                  <span className={styles.exampleTitle}>{title}</span>
                  <span className={styles.examplePrompt}>{prompt}</span>
                </button>
              ))}
            </div>
          )}
          <div className={intro ? `${styles.composerIntro} ${docking ? styles.composerDocking : ''}` : styles.composer}>
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
          {intro && !docking && <button className={styles.backLinkIntro} onClick={onClose}><ChevronLeft size={14} /> Back to dashboards</button>}
        </aside>

        {!intro && (
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
                  {viewMode === 'preview' && config?.html && (
                    <div className={styles.refreshGroup} ref={refreshMenuRef}>
                      <button
                        className={styles.ghostBtn}
                        onClick={refreshNow}
                        title={lastRefreshedAt ? `Last refreshed ${new Date(lastRefreshedAt).toLocaleTimeString()}` : 'Refresh'}
                      >
                        <RefreshCw size={13} /> Refresh
                      </button>
                      <button
                        className={autoRefreshMs ? styles.refreshMenuBtnActive : styles.refreshMenuBtn}
                        onClick={() => setShowRefreshMenu((v) => !v)}
                        title="Auto-refresh"
                        aria-label="Auto-refresh options"
                      >
                        {autoRefreshMs ? AUTO_REFRESH_LABEL[autoRefreshMs] : <ChevronDown size={13} />}
                      </button>
                      {showRefreshMenu && (
                        <div className={styles.refreshMenuPop}>
                          {AUTO_REFRESH_OPTIONS.map(({ ms, label }) => (
                            <button
                              key={ms}
                              className={autoRefreshMs === ms ? styles.refreshMenuItemActive : styles.refreshMenuItem}
                              onClick={() => selectAutoRefresh(ms)}
                            >
                              {label}{autoRefreshMs === ms && <Check size={12} />}
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                  <button
                    className={showAlerts ? styles.queriesBtnActive : styles.queriesBtn}
                    onClick={() => { setShowQueries(false); setShowHistory(false); toggleAlerts() }}
                    disabled={!savedId}
                    title={!savedId ? 'Save the dashboard first' : undefined}
                  >
                    <BellRing size={13} /> Alerts{alerts.filter((a) => a.isEnabled).length > 0 ? ` ${alerts.filter((a) => a.isEnabled).length}` : ''}
                  </button>
                  <button
                    className={showHistory ? styles.queriesBtnActive : styles.queriesBtn}
                    onClick={() => { setShowQueries(false); setShowAlerts(false); toggleHistory() }}
                    disabled={!savedId}
                    title={!savedId ? 'Save the dashboard first' : undefined}
                  >
                    <History size={13} /> History
                  </button>
                  <button
                    className={showQueries ? styles.queriesBtnActive : styles.queriesBtn}
                    onClick={() => { setShowHistory(false); setShowAlerts(false); setShowQueries((v) => !v) }}
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

                {showAlerts && (
                  <aside className={styles.queriesPanel}>
                    <div className={styles.queriesPanelHead}>
                      <span>Alerts</span>
                      <button onClick={() => setShowAlerts(false)} aria-label="Close"><X size={14} /></button>
                    </div>
                    <div className={styles.queriesPanelList}>
                      <div className={styles.alertComposer}>
                        <textarea
                          className={styles.alertComposerInput}
                          rows={2}
                          placeholder="Alert if… (e.g. “alert if the error rate exceeds 5% in the last hour”)"
                          value={newAlertText}
                          onChange={(e) => setNewAlertText(e.target.value)}
                        />
                        <input
                          className={styles.alertComposerEmail}
                          placeholder="Email to notify (optional)"
                          value={newAlertEmail}
                          onChange={(e) => setNewAlertEmail(e.target.value)}
                        />
                        <button
                          className={styles.primaryBtnSm}
                          onClick={createAlert}
                          disabled={!newAlertText.trim() || savingAlert}
                        >
                          {savingAlert ? <Loader2 size={13} className={styles.spin} /> : <BellRing size={13} />} Add alert
                        </button>
                      </div>

                      {alertsLoading ? (
                        <div className={styles.queriesEmpty}><Loader2 size={14} className={styles.spin} /></div>
                      ) : alerts.length === 0 ? (
                        <div className={styles.queriesEmpty}>No alerts yet — describe a condition above and the DeepSQL agent will check it on a schedule.</div>
                      ) : (
                        alerts.map((a) => (
                          <div key={a.id} className={styles.alertCard}>
                            <div className={styles.alertCardHead}>
                              <span className={a.isEnabled ? styles.alertBadgeOn : styles.alertBadgeOff}>
                                {a.isEnabled ? 'On' : 'Off'}
                              </span>
                              <span className={styles.alertInterval}>every {a.checkIntervalMinutes}m</span>
                              <div className={styles.alertCardActions}>
                                <button className={styles.alertTextBtn} onClick={() => toggleAlertEnabled(a)}>
                                  {a.isEnabled ? 'Disable' : 'Enable'}
                                </button>
                                <button className={styles.queryCopyBtn} onClick={() => deleteAlert(a.id)} title="Delete alert" aria-label="Delete alert">
                                  <Trash2 size={12} />
                                </button>
                              </div>
                            </div>
                            <div className={styles.alertCondition}>{a.conditionText}</div>
                            {a.lastVerdict && (
                              <div className={a.lastVerdict === 'FIRED' ? styles.alertLastFired : (a.lastVerdict === 'ERROR' ? styles.alertLastError : styles.alertLastOk)}>
                                {a.lastVerdict === 'FIRED' ? '🔔 Fired' : (a.lastVerdict === 'ERROR' ? '⚠ Check failed' : '✓ OK')} — {a.lastVerdict === 'ERROR' ? a.lastError : a.lastReason}
                              </div>
                            )}
                          </div>
                        ))
                      )}
                    </div>
                  </aside>
                )}

                {showHistory && (
                  <aside className={styles.queriesPanel}>
                    <div className={styles.queriesPanelHead}>
                      <span>Version history</span>
                      <button onClick={() => setShowHistory(false)} aria-label="Close"><X size={14} /></button>
                    </div>
                    <div className={styles.queriesPanelList}>
                      {versionsLoading ? (
                        <div className={styles.queriesEmpty}><Loader2 size={14} className={styles.spin} /></div>
                      ) : versions.length === 0 ? (
                        <div className={styles.queriesEmpty}>No earlier versions yet — one is saved automatically each time you (or the agent) change this dashboard.</div>
                      ) : (
                        <>
                          <div className={styles.versionCard}>
                            <div className={styles.versionMeta}>
                              <span className={styles.versionName}>{name}</span>
                              <span className={styles.versionSub}>Current</span>
                              {versionHtml(versions[0]) && (
                                <span className={styles.versionDiff}>{summarizeChange(versionHtml(versions[0]), config?.html) || 'no visible change'}</span>
                              )}
                            </div>
                            <span className={styles.versionCurrentBadge}>Live</span>
                          </div>
                          {versions.map((v, i) => {
                            const olderHtml = versionHtml(versions[i + 1]) // one snapshot further back — this version's "before"
                            const diff = olderHtml ? summarizeChange(olderHtml, versionHtml(v)) : null
                            return (
                              <div key={v.id} className={styles.versionCard}>
                                <div className={styles.versionMeta}>
                                  <span className={styles.versionName}>{v.name || 'Untitled'}</span>
                                  <span className={styles.versionSub}>{VERSION_TRIGGER_LABEL[v.trigger] || v.trigger} · {versionRelTime(v.createdAt)}</span>
                                  {diff && <span className={styles.versionDiff}>{diff}</span>}
                                </div>
                                <div className={styles.versionActions}>
                                  <button
                                    className={styles.versionPreviewBtn}
                                    onClick={() => setPreviewVersion(v)}
                                    disabled={!versionHtml(v)}
                                    title="Preview this version"
                                  >
                                    <Eye size={12} />
                                  </button>
                                  <button
                                    className={styles.versionRestoreBtn}
                                    onClick={() => restoreVersion(v)}
                                    disabled={restoringId === v.id || thinking}
                                    title={thinking ? 'Wait for the current build to finish first' : 'Restore this version'}
                                  >
                                    {restoringId === v.id ? <Loader2 size={12} className={styles.spin} /> : <RotateCcw size={12} />} Restore
                                  </button>
                                </div>
                              </div>
                            )
                          })}
                        </>
                      )}
                    </div>
                  </aside>
                )}

                {previewVersion && (
                  <div className={styles.previewOverlay} onClick={() => setPreviewVersion(null)}>
                    <div className={styles.previewModal} onClick={(e) => e.stopPropagation()}>
                      <div className={styles.previewModalHead}>
                        <span>{previewVersion.name || 'Untitled'} · {VERSION_TRIGGER_LABEL[previewVersion.trigger] || previewVersion.trigger} · {versionRelTime(previewVersion.createdAt)}</span>
                        <button onClick={() => setPreviewVersion(null)} aria-label="Close preview"><X size={14} /></button>
                      </div>
                      <div className={styles.previewModalBody}>
                        <DashboardArtifact connectionId={connectionId} html={versionHtml(previewVersion)} onError={() => {}} />
                      </div>
                      <div className={styles.previewModalFoot}>
                        <button
                          className={styles.versionRestoreBtn}
                          onClick={() => { const v = previewVersion; setPreviewVersion(null); restoreVersion(v) }}
                          disabled={thinking}
                        >
                          <RotateCcw size={12} /> Restore this version
                        </button>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </>
          ) : thinking ? (
            <div className={styles.newCanvas}>
              <div className={styles.newCanvasInner}>
                <span className={styles.newIconPulse}><Sparkles size={22} color="#534AB7" /></span>
                <h2 className={styles.newTitle}>Building your dashboard…</h2>
                <p className={styles.newSub}>
                  {steps.length > 0 ? steps[steps.length - 1].message : 'Grounding on your schema and verifying every query…'}
                  <span className={styles.buildElapsed}> · {elapsed}s</span>
                </p>
                <p key={tipIndex} className={styles.buildTip}>{BUILD_TIPS[tipIndex]}</p>
              </div>
            </div>
          ) : (
            <div className={styles.newCanvas}>
              <div className={styles.newCanvasInner}>
                <span className={styles.newIcon}><Sparkles size={22} color="#534AB7" /></span>
                <h2 className={styles.newTitle}>Nothing built here yet</h2>
                <p className={styles.newSub}>This dashboard doesn’t have a build yet — the chat on the left may just be planning so far. Ask for a chart to get started.</p>
                <button className={styles.backLink} onClick={onClose}><ChevronLeft size={14} /> Back to dashboards</button>
              </div>
            </div>
          )}
        </main>
        )}
      </div>
    </div>
  )
}
