/**
 * Brain Agent Store — localStorage-backed agent registry.
 *
 * Each agent: id, name, description, task, connectionId, schedule,
 *             scheduleTime, enabled, createdAt, updatedAt,
 *             deployed, deployedAt, deploymentSnapshot (last good output)
 *
 * Each run:   id, userMessage, startedAt, status, output, duration,
 *             isTest (bool), isScheduled (bool), plan, queries
 *
 * chatId is stored per-agent so follow-up replies keep conversation context.
 */

const AGENTS_KEY  = 'brain_agents_v1'
const RUNS_KEY    = 'brain_runs_v1'
const CHATID_KEY  = 'brain_chatids_v1'

function load(key) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

function save(key, data) {
  try {
    localStorage.setItem(key, JSON.stringify(data))
  } catch { /* quota exceeded — silently skip */ }
}

// ── Agents ──────────────────────────────────────────────────────────────────

export function getAgents() {
  const map = load(AGENTS_KEY)
  return Object.values(map).sort(
    (a, b) => new Date(b.createdAt) - new Date(a.createdAt)
  )
}

export function getAgent(id) {
  return load(AGENTS_KEY)[id] || null
}

export function saveAgent(agent) {
  const map = load(AGENTS_KEY)
  const now = new Date().toISOString()
  const existing = map[agent.id]
  map[agent.id] = {
    ...agent,
    createdAt: existing?.createdAt || now,
    updatedAt: now,
  }
  save(AGENTS_KEY, map)
  return map[agent.id]
}

export function deleteAgent(id) {
  const map = load(AGENTS_KEY)
  delete map[id]
  save(AGENTS_KEY, map)

  const runs = load(RUNS_KEY)
  delete runs[id]
  save(RUNS_KEY, runs)

  const chatIds = load(CHATID_KEY)
  delete chatIds[id]
  save(CHATID_KEY, chatIds)
}

export function toggleAgent(id) {
  const map = load(AGENTS_KEY)
  if (!map[id]) return
  map[id].enabled = !map[id].enabled
  map[id].updatedAt = new Date().toISOString()
  save(AGENTS_KEY, map)
  return map[id]
}

// ── Chat ID (conversation continuity) ────────────────────────────────────────

export function getChatId(agentId) {
  return load(CHATID_KEY)[agentId] || null
}

export function setChatId(agentId, chatId) {
  const map = load(CHATID_KEY)
  map[agentId] = chatId
  save(CHATID_KEY, map)
}

export function clearChatId(agentId) {
  const map = load(CHATID_KEY)
  delete map[agentId]
  save(CHATID_KEY, map)
}

// ── Runs (conversation turns) ─────────────────────────────────────────────────
// Stored oldest-first; returned oldest-first so UI renders chronologically.

export function getRuns(agentId) {
  const runs = load(RUNS_KEY)
  return runs[agentId] || []
}

export function addRun(agentId, run) {
  const runs = load(RUNS_KEY)
  if (!runs[agentId]) runs[agentId] = []
  runs[agentId].push(run)
  // Keep last 100 turns per agent
  if (runs[agentId].length > 100) runs[agentId] = runs[agentId].slice(-100)
  save(RUNS_KEY, runs)
}

export function updateRun(agentId, runId, patch) {
  const runs = load(RUNS_KEY)
  if (!runs[agentId]) return
  const idx = runs[agentId].findIndex((r) => r.id === runId)
  if (idx === -1) return
  runs[agentId][idx] = { ...runs[agentId][idx], ...patch }
  save(RUNS_KEY, runs)
}

export function clearRuns(agentId) {
  const runs = load(RUNS_KEY)
  delete runs[agentId]
  save(RUNS_KEY, runs)
  clearChatId(agentId)
}

export function generateId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

// ── Deployment helpers ────────────────────────────────────────────────────────

export function deployAgent(agentId, snapshot) {
  const map = load(AGENTS_KEY)
  if (!map[agentId]) return null
  map[agentId] = {
    ...map[agentId],
    deployed: true,
    deployedAt: new Date().toISOString(),
    enabled: true,
    deploymentSnapshot: snapshot || null,
    updatedAt: new Date().toISOString(),
  }
  save(AGENTS_KEY, map)
  return map[agentId]
}

export function undeployAgent(agentId) {
  const map = load(AGENTS_KEY)
  if (!map[agentId]) return null
  map[agentId] = {
    ...map[agentId],
    deployed: false,
    deployedAt: null,
    updatedAt: new Date().toISOString(),
  }
  save(AGENTS_KEY, map)
  return map[agentId]
}

// ── Scheduler helpers ─────────────────────────────────────────────────────────

/**
 * Returns the next scheduled run Date for an agent, or null if not schedulable.
 */
export function getNextRunDate(agent) {
  if (!agent.deployed || !agent.enabled || agent.schedule === 'manual') return null
  const now = new Date()
  const [h, m] = (agent.scheduleTime || '09:00').split(':').map(Number)

  if (agent.schedule === 'hourly') {
    const next = new Date(now)
    next.setMinutes(0, 0, 0)
    next.setHours(next.getHours() + 1)
    return next
  }
  if (agent.schedule === 'daily') {
    const next = new Date(now)
    next.setHours(h, m, 0, 0)
    if (next <= now) next.setDate(next.getDate() + 1)
    return next
  }
  if (agent.schedule === 'weekly') {
    const next = new Date(now)
    next.setHours(h, m, 0, 0)
    const daysUntilMon = (1 - now.getDay() + 7) % 7 || 7
    next.setDate(now.getDate() + daysUntilMon)
    return next
  }
  if (agent.schedule === 'monthly') {
    const next = new Date(now.getFullYear(), now.getMonth() + 1, 1, h, m, 0)
    return next
  }
  return null
}

/**
 * Returns true if a deployed agent is due to run right now.
 * lastRunTime is a Date or null.
 */
export function isDue(agent, now, lastRunTime) {
  if (!agent.deployed || !agent.enabled || agent.schedule === 'manual') return false
  if (!lastRunTime) return true // never run — fire immediately

  const [h, m] = (agent.scheduleTime || '09:00').split(':').map(Number)
  const elapsed = now - lastRunTime

  if (agent.schedule === 'hourly') return elapsed >= 60 * 60 * 1000

  if (agent.schedule === 'daily') {
    const target = new Date(now)
    target.setHours(h, m, 0, 0)
    return now >= target && lastRunTime < target
  }
  if (agent.schedule === 'weekly') {
    const target = new Date(now)
    const dayOfWeek = now.getDay()
    const daysToMon = dayOfWeek === 0 ? 6 : dayOfWeek - 1
    target.setDate(now.getDate() - daysToMon)
    target.setHours(h, m, 0, 0)
    return now >= target && lastRunTime < target
  }
  if (agent.schedule === 'monthly') {
    const target = new Date(now.getFullYear(), now.getMonth(), 1, h, m, 0)
    return now >= target && lastRunTime < target
  }
  return false
}
