import { create } from 'zustand'
import { useShallow } from 'zustand/react/shallow'
import { generateDashboardStream } from '@/lib/dashboardGenerator'
import { savedDashboardsAPI } from '@/lib/api/client'

// Keeps each dashboard workspace's in-flight generation (chat messages,
// streaming steps, the built config, the abort fn) alive in memory across
// component mount/unmount. DashboardWorkspace used to hold all of this in
// component-local useState and abort the SSE stream on unmount — so
// navigating away from the workspace screen mid-build cancelled the agent
// turn and dropped the chat. This module-level store outlives the component
// for as long as the tab stays open; DashboardWorkspace becomes a thin
// subscriber that reads/writes a session instead of owning the state.
//
// A full page close/reload still wipes this module's memory — nothing in JS
// survives that. What survives is the BACKEND's own copy: DashboardGeneration-
// Controller now persists each turn itself (user message on submit, the
// answer on completion) regardless of whether this tab is still connected —
// see beginGenerationTurn/appendAgentReply/completeBuildTurn/appendErrorReply
// in SavedDashboardService. `resumeIfRunning` below is how a freshly (re)opened
// tab picks that back up: if the persisted row says a generation is still
// RUNNING, it polls until the backend's own write flips it back to IDLE,
// instead of assuming nothing is happening.
//
// Sessions are keyed by the dashboard id once one exists, or `new:<connectionId>`
// for a not-yet-saved dashboard (DashboardWorkspace never remounts with a
// different `dashboard`/`connectionId` prop without an unmount in between, so
// that key is stable for a whole mount — see DashboardsSection.jsx). The
// transient `new:` key is aliased to the real id the instant the backend
// resolves/creates one (now typically within one fast round-trip — see
// `onCreated` in submitPrompt — rather than only after a build fully
// completes), so the same mounted component keeps reading live data through
// the rename; `releaseAlias` frees the transient slot on unmount so a LATER
// brand-new dashboard on the same connection doesn't inherit a finished one's
// chat.

const emptySession = () => ({
  messages: [],
  thinking: false,
  steps: [],
  startedAt: null,
  config: null,
  savedId: null,
  dirty: false,
  abort: null,
  // Progressive build: liveShell is the dashboard-shell chunk for the turn
  // currently in flight — a separate slot from `config` (the last COMPLETED
  // build) so a live build renders its own in-progress canvas without
  // clobbering (or being clobbered by) whatever was there before. Cleared
  // the instant the turn resolves (onDone/onChat/onError), since `config`
  // takes over as the source of truth from then on.
  liveShell: null,
  liveWidget: null,
  // Every widget chunk seen this turn (id -> html), so onDone can check whether
  // the final assembled document is identical to what's already live-rendered
  // (see submitPrompt's onDone) — if so, the iframe is left alone instead of
  // reloading and re-running every widget's query a second time.
  liveWidgets: null,
  // What DashboardWorkspace actually feeds DashboardArtifact's `html` prop once
  // a build finishes — see onDone. null while a session has never finished a
  // build (falls back to config?.html), or after a build that DID need a real
  // reload (self-review changed something), in which case it's just config.html.
  renderHtml: null,
})

// A single stable reference for "no session yet" reads. useDashboardSession's
// selector must never allocate a fresh fallback object per call — with
// useSyncExternalStore (which Zustand's create() hook is built on), a
// selector returning a new reference every invocation looks like the store
// changed on every render, which is an infinite render loop, not a style nit.
const EMPTY_SESSION = Object.freeze(emptySession())

// Mirrors SavedDashboardService.STALE_RUNNING_THRESHOLD — if the backend
// hasn't flipped a turn back to IDLE by then, it's not coming back (most
// likely a crashed backend), so stop polling and say so instead of spinning
// forever.
const MAX_POLL_WAIT_MS = 20 * 60 * 1000
const POLL_INTERVAL_MS = 3000

// Mirrors DashboardAgentService.substituteWidgetSlot (same regex shape) so we
// can reconstruct, on the frontend, what the fully-assembled document would
// look like from the shell + widget chunks already streamed — purely to
// compare against the backend's own final assembly and decide whether the
// iframe needs to reload at all (see onDone below).
function substituteWidgetSlot(shellHtml, widgetId, widgetHtml) {
  const escapedId = widgetId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const slot = new RegExp(`(<[a-zA-Z0-9]+[^>]*data-widget="${escapedId}"[^>]*>)([\\s\\S]*?)(</[a-zA-Z0-9]+>)`)
  if (slot.test(shellHtml)) return shellHtml.replace(slot, (_m, open, _inner, close) => open + widgetHtml + close)
  const selfClosing = new RegExp(`<[a-zA-Z0-9]+[^>]*data-widget="${escapedId}"[^>]*/>`)
  if (selfClosing.test(shellHtml)) return shellHtml.replace(selfClosing, `<div data-widget="${widgetId}">${widgetHtml}</div>`)
  return shellHtml
}

function assembleLiveHtml(liveShell, liveWidgets) {
  if (!liveShell) return null
  let html = liveShell
  for (const [id, widgetHtml] of Object.entries(liveWidgets || {})) {
    html = substituteWidgetSlot(html, id, widgetHtml)
  }
  return html
}

function resolveKeyIn(state, key) {
  let k = key
  const seen = new Set()
  while (state.aliases[k] && !seen.has(k)) {
    seen.add(k)
    k = state.aliases[k]
  }
  return k
}

export const useDashboardChatStore = create((set, get) => ({
  sessions: {},
  aliases: {},

  resolveKey: (key) => resolveKeyIn(get(), key),

  getSession: (key) => {
    const state = get()
    return state.sessions[resolveKeyIn(state, key)] || EMPTY_SESSION
  },

  // Seeds a session the first time this key is ever opened (e.g. restoring a
  // saved dashboard's persisted chat/config); never touches an in-progress or
  // already-resumed one, so re-opening a workspace that's still generating
  // (or that finished while the user was elsewhere) shows the live state.
  ensureSession: (key, seed) => {
    set((state) => {
      const k = resolveKeyIn(state, key)
      if (state.sessions[k]) return state
      return { sessions: { ...state.sessions, [k]: { ...emptySession(), ...seed } } }
    })
  },

  patchSession: (key, patch) => {
    set((state) => {
      const k = resolveKeyIn(state, key)
      const cur = state.sessions[k] || emptySession()
      const next = typeof patch === 'function' ? patch(cur) : patch
      return { sessions: { ...state.sessions, [k]: { ...cur, ...next } } }
    })
  },

  // Frees a transient `new:<connectionId>` slot once the component that owned
  // it has moved on (its dashboard got an id and the component unmounted) —
  // otherwise the NEXT "+ New dashboard" on the same connection would resolve
  // straight through to the finished one's chat/config.
  releaseAlias: (key) => {
    set((state) => {
      if (!(key in state.aliases)) return state
      const { [key]: _removed, ...rest } = state.aliases
      return { aliases: rest }
    })
  },

  // Moves a session from a transient key onto the real dashboard id the
  // backend resolved/created for it, aliasing the old key so callers that
  // still reference it (patchSession/getSession/resolveKey all chase aliases)
  // keep reading live data through the rename.
  adoptRealId: (key, newId) => {
    set((state) => {
      const k = resolveKeyIn(state, key)
      if (k === newId || state.sessions[newId]) return state
      const cur = state.sessions[k] || emptySession()
      const { [k]: _moved, ...restSessions } = state.sessions
      return {
        sessions: { ...restSessions, [newId]: { ...cur, savedId: newId } },
        aliases: { ...state.aliases, [k]: newId },
      }
    })
  },

  submitPrompt: (key, connectionId, prompt) => {
    const state = get()
    const session = state.sessions[resolveKeyIn(state, key)] || emptySession()
    const text = (prompt || '').trim()
    if (!text || session.thinking) return
    if (session.abort) session.abort()

    get().patchSession(key, (cur) => ({
      messages: [...cur.messages, { role: 'user', text }],
      steps: [],
      thinking: true,
      startedAt: Date.now(),
      liveShell: null,
      liveWidget: null,
      liveWidgets: null,
      // renderHtml is DELIBERATELY left as-is here (not reset to null): while
      // this new turn is grounding/verifying (liveShell not set yet), the html
      // prop still falls back to renderHtml || config.html — resetting it here
      // would jump the fallback to config.html (the fully-assembled string,
      // different from whatever bare-shell string is actually still in the
      // iframe's srcDoc from the LAST turn's no-reload path) and force a
      // spurious reload + duplicate queries the moment a new prompt is
      // submitted, before the new build has even started streaming.
    }))

    const currentConfig = get().getSession(key).config
    const dashboardId = get().getSession(key).savedId

    const abort = generateDashboardStream(connectionId, text, currentConfig, dashboardId, {
      // The backend has already durably recorded the user's message under
      // this id, before doing any slow agent work — adopt it immediately so
      // a reload from here on (even mid-generation) can find this turn.
      onCreated: (data) => {
        if (data?.dashboardId) get().adoptRealId(key, String(data.dashboardId))
      },
      onStep: (s) => get().patchSession(key, (cur) => ({ steps: [...cur.steps, s] })),
      // Progressive build — a dashboard-shell or dashboard-widget chunk landed
      // well before the turn finishes. The shell sets up the live canvas once;
      // each widget after that is a one-shot mount (DashboardWorkspace posts it
      // into the already-loaded iframe via DashboardArtifact's ref, then this
      // slot's job is done — liveWidget is "last arrived", not an accumulating
      // list, since the iframe itself is what now holds that DOM).
      onChunk: (chunk) => get().patchSession(key, (cur) => (
        chunk?.kind === 'shell'
          ? { liveShell: chunk.html }
          : {
              liveWidget: { id: chunk.id, html: chunk.html, seq: (cur.liveWidget?.seq || 0) + 1 },
              liveWidgets: { ...cur.liveWidgets, [chunk.id]: chunk.html },
            }
      )),
      onChat: (reply) => get().patchSession(key, (cur) => ({
        thinking: false,
        steps: [],
        abort: null,
        liveShell: null,
        liveWidget: null,
        liveWidgets: null,
        messages: [...cur.messages, { role: 'agent', text: reply || '…' }],
      })),
      onDone: (next) => {
        // Belt-and-braces: a chat-shaped payload must never hit the "built"
        // path (that appends the canned save line and would clobber a real
        // artifact).
        if (!next?.html || next?.chat) {
          get().patchSession(key, (cur) => ({
            thinking: false,
            steps: [],
            abort: null,
            liveShell: null,
            liveWidget: null,
            liveWidgets: null,
            messages: [...cur.messages, { role: 'agent', text: next?.reply || '…' }],
          }))
          return
        }
        // Persistence is the BACKEND's job now (DashboardGenerationController
        // calls SavedDashboardService.completeBuildTurn the instant the agent
        // finishes, regardless of whether this tab is still connected) — this
        // patch is just so the UI updates instantly without waiting on a
        // round-trip. Keep this message textually identical to the one
        // completeBuildTurn appends server-side.
        get().patchSession(key, (cur) => {
          // config.html is always the backend's real final document (persisted,
          // shown in Source, shared) — never rewritten. Separately, renderHtml is
          // what DashboardWorkspace actually feeds DashboardArtifact's `html` prop:
          // if self-review didn't touch anything, the reconstruction from chunks
          // already streamed to this tab is byte-identical to next.html, so
          // renderHtml keeps the SAME string reference the iframe is already
          // showing (the live shell + injected widgets) — the srcDoc attribute
          // never changes, so the browser never reloads the iframe and no
          // widget's query re-runs a second time. If self-review DID change
          // something, the strings differ and renderHtml becomes next.html,
          // which legitimately needs a fresh iframe load to show the fix.
          const reconstructed = assembleLiveHtml(cur.liveShell, cur.liveWidgets)
          const noReloadNeeded = reconstructed === next.html
          return {
            thinking: false,
            steps: [],
            abort: null,
            config: next,
            renderHtml: noReloadNeeded ? cur.liveShell : next.html,
            liveShell: null,
            liveWidget: null,
            liveWidgets: null,
            messages: [...cur.messages, { role: 'agent', text: 'Done — built and verified against your data. Saved as a draft — tell me what to change.' }],
          }
        })
      },
      onError: (e) => get().patchSession(key, (cur) => ({
        thinking: false,
        steps: [],
        abort: null,
        liveShell: null,
        liveWidget: null,
        liveWidgets: null,
        messages: [...cur.messages, { role: 'agent', text: `⚠ ${e?.message || 'Generation failed.'}`, error: true }],
      })),
    })
    get().patchSession(key, { abort })
  },

  // Called on mount for an existing dashboard whose freshly-fetched row says
  // generationStatus === "RUNNING" — a turn was in flight when this tab
  // wasn't around (or reloaded mid-generation) to receive it live. Polls the
  // saved dashboard until the backend's own completion write flips it back to
  // IDLE, then adopts the now-persisted messages/config, instead of assuming
  // nothing is happening. Does nothing if a live session (with its own SSE
  // connection) already exists for this key.
  resumeIfRunning: (key, dashboardId, generationStatus, generationStartedAt) => {
    if (generationStatus !== 'RUNNING') return
    if (get().getSession(key).thinking) return // already live via this tab's own SSE
    const startedAtMs = generationStartedAt ? new Date(generationStartedAt).getTime() : Date.now()
    get().patchSession(key, { thinking: true, startedAt: startedAtMs, steps: [] })

    const poll = async () => {
      // Something else already resolved this session (e.g. the backend
      // finished and a normal onDone/onChat already fired via a live SSE
      // connection opened in the meantime) — stop politely.
      if (!get().getSession(key).thinking) return
      if (Date.now() - startedAtMs > MAX_POLL_WAIT_MS) {
        get().patchSession(key, (cur) => ({
          thinking: false,
          messages: [...cur.messages, { role: 'agent', text: '⚠ This looks stuck — try sending your message again.', error: true }],
        }))
        return
      }
      try {
        const res = await savedDashboardsAPI.getDashboardById(dashboardId)
        const d = res?.savedDashboard || res?.dashboard || res
        if (d?.generationStatus === 'RUNNING') {
          setTimeout(poll, POLL_INTERVAL_MS)
          return
        }
        let cfg = null
        try { cfg = d?.dashboardConfig ? JSON.parse(d.dashboardConfig) : null } catch { cfg = null }
        let msgs = null
        try { msgs = d?.chatMessages ? JSON.parse(d.chatMessages) : null } catch { msgs = null }
        get().patchSession(key, (cur) => ({
          thinking: false,
          steps: [],
          config: cfg?.html ? cfg : null,
          messages: Array.isArray(msgs) && msgs.length ? msgs : cur.messages,
        }))
      } catch {
        // Transient network hiccup while polling — retry rather than give up
        // on the first blip.
        setTimeout(poll, POLL_INTERVAL_MS)
      }
    }
    setTimeout(poll, POLL_INTERVAL_MS)
  },

  // Explicit, one-off saves — the Save button, and a manual Source edit
  // (DashboardWorkspace's applySource). Chat-turn persistence no longer goes
  // through here (the backend does that itself); this remains the fallback
  // path for the rare case a Source edit happens before any chat turn has
  // ever resolved a real dashboard id.
  persistDraft: async (key, connectionId, cfg, msgs) => {
    if (!cfg) return false
    const state = get()
    const k = resolveKeyIn(state, key)
    const session = state.sessions[k] || emptySession()
    const body = {
      connectionId,
      name: cfg.title || 'Untitled dashboard',
      description: cfg.description || '',
      dashboardConfig: JSON.stringify(cfg),
      chatMessages: JSON.stringify(msgs || session.messages),
      isFavorite: false,
    }
    try {
      if (session.savedId) {
        await savedDashboardsAPI.updateDashboard(session.savedId, body)
      } else {
        const res = await savedDashboardsAPI.createDashboard(body)
        const created = res?.savedDashboard || res?.dashboard || res
        if (created?.id) get().adoptRealId(key, String(created.id))
      }
      get().patchSession(key, { dirty: false })
      return true
    } catch (e) {
      get().patchSession(key, { dirty: true })
      throw e
    }
  },
}))

// Selector hooks for optimized re-renders — a component only re-renders when
// the specific slice it reads changes, not on every session's update.
export const useDashboardSession = (key) =>
  useDashboardChatStore((state) => state.sessions[resolveKeyIn(state, key)] || EMPTY_SESSION)

// useShallow: the returned object is a fresh literal every call, but the
// action values inside it are stable for the store's lifetime — without this,
// the same infinite-snapshot-loop bug as above (a "new" object every
// getSnapshot call) would fire on every render, not just when a session changes.
export const useDashboardChatActions = () =>
  useDashboardChatStore(useShallow((state) => ({
    ensureSession: state.ensureSession,
    patchSession: state.patchSession,
    releaseAlias: state.releaseAlias,
    adoptRealId: state.adoptRealId,
    submitPrompt: state.submitPrompt,
    resumeIfRunning: state.resumeIfRunning,
    persistDraft: state.persistDraft,
  })))
