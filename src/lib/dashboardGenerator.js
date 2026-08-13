import { dashboardGenAPI } from '@/lib/api/client'

// Dashboard generation runs the embedded DeepSQL Agent as a CODING agent
// (customized Hermes runtime — see agent/README.md).
// (DashboardAgentService): it grounds on business rules + schema, verifies every
// query with execute_sql, then writes the whole dashboard as a self-contained
// HTML document — streaming its steps to the UI. The artifact renders in a
// sandboxed iframe and fetches data via the read-only deepsql.query bridge.

// Streaming generate. Handlers: onStep({type,message}), onDone(config), onError(Error),
// onChat(replyText) for a plain conversational reply (no dashboard change — e.g. "hi").
// Returns an abort fn. The final config is the artifact spec
// ({version:3, renderMode:'artifact', title, html}).
export function generateDashboardStream(connectionId, prompt, currentConfig, { onStep, onDone, onError, onChat } = {}) {
  return dashboardGenAPI.generateStream(connectionId, prompt, currentConfig, {
    onStep,
    onDone: (data) => {
      // Chat-only can arrive as event:chat (reply at top level) or legacy done
      // with dashboardConfig.chat=true. Treat either as a plain reply — never as
      // a successful build (that path hardcodes "Done — built…" in the UI).
      const cfg = data?.dashboardConfig
      const chatReply = (typeof data?.reply === 'string' && data.reply) || cfg?.reply || ''
      if (data?.chat === true || cfg?.chat === true || (chatReply && !cfg?.html && !cfg?.renderMode)) {
        onChat && onChat(chatReply)
        return
      }
      if (!cfg) {
        onError && onError(new Error(data?.error || 'Generation returned no dashboard.'))
        return
      }
      onDone && onDone({ ...cfg, updatedAt: new Date().toISOString() })
    },
    onError,
  })
}

// Blocking variant (kept for non-streaming callers / tests).
export async function generateDashboard(connectionId, prompt, currentConfig) {
  const res = await dashboardGenAPI.generate(connectionId, prompt, currentConfig)
  if (res?.success && res.dashboardConfig) {
    return { ...res.dashboardConfig, updatedAt: new Date().toISOString() }
  }
  throw new Error(res?.error || 'Generation failed')
}
