/**
 * Brain init stage helpers — keep in sync with
 * com.dbaagent.model.InitStage#isTerminal().
 */

const TERMINAL_STAGES = new Set([
  'COMPLETED',
  'FAILED',
  'ERROR',
  'NEEDS_ATTENTION',
])

/** Pipeline finished (success, failure, or incomplete coverage). Stop polling. */
export function isInitTerminal(stage) {
  return !stage || TERMINAL_STAGES.has(stage)
}

/** Still running a non-terminal stage (includes NONE? no — NONE is idle). */
export function isInitRunning(stage) {
  return Boolean(stage) && stage !== 'NONE' && !TERMINAL_STAGES.has(stage)
}

export function isInitComplete(stage) {
  return stage === 'COMPLETED'
}

export function isInitNeedsAttention(stage) {
  return stage === 'NEEDS_ATTENTION'
}

export function isInitFailed(stage) {
  return stage === 'FAILED' || stage === 'ERROR'
}
