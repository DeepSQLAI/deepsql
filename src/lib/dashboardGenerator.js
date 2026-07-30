import { dashboardGenAPI } from '@/lib/api/client'

// Dashboard generation runs the embedded Hermes agent as a CODING agent
// (DashboardAgentService): it grounds on business rules + schema, verifies every
// query with execute_sql, then writes the whole dashboard as a self-contained
// HTML document — streaming its steps to the UI. The artifact renders in a
// sandboxed iframe and fetches data via the read-only deepsql.query bridge.

// Streaming generate. Handlers: onStep({type,message}), onDone(config), onError(Error).
// Returns an abort fn. The final config is the artifact spec
// ({version:3, renderMode:'artifact', title, html}).
export function generateDashboardStream(connectionId, prompt, currentConfig, { onStep, onDone, onError } = {}) {
  return dashboardGenAPI.generateStream(connectionId, prompt, currentConfig, {
    onStep,
    onDone: (data) => {
      if (!data?.dashboardConfig) {
        onError && onError(new Error(data?.error || 'Generation returned no dashboard.'))
        return
      }
      onDone && onDone({ ...data.dashboardConfig, updatedAt: new Date().toISOString() })
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
