/**
 * Suggestion chips only after the user corrects or teaches. A clean first
 * answer must not spawn a save bubble.
 *
 * Phrase contains() — not a fat regex — so a long transcript cannot ReDoS.
 */
export const FEEDBACK_PHRASES = [
  "that's wrong",
  'that is wrong',
  "that's not",
  'that is not',
  'incorrect',
  'actually ',
  'instead',
  'should be',
  'should use',
  'you should',
  "don't use",
  'do not use',
  'never use',
  'always use',
  'always filter',
  'always join',
  'always exclude',
  'we use',
  'we always',
  'we never',
  'not that',
  'not the ',
  'pin this',
  'pin that',
  'remember this',
  'remember:',
  'save this',
  'save that',
  'use this',
  'use that',
  'too high',
  'too low',
  'off by',
  'the right ',
]

export function looksLikeUserFeedback(userText) {
  if (!userText || !String(userText).trim()) return false
  const q = String(userText).toLowerCase().trim()
  if (q.startsWith('no,') || q.startsWith('no ') || q.startsWith('no-')
    || q.startsWith('no—') || q.startsWith('nope')) {
    return true
  }
  return FEEDBACK_PHRASES.some((phrase) => q.includes(phrase))
}

export function proposalTargetKey(proposal) {
  if (!proposal) return ''
  return `${proposal.tableName || ''}::${proposal.columnName || ''}`
}

export function isSuppressedProposal(proposal, suppressedTargets = []) {
  const key = proposalTargetKey(proposal)
  return Boolean(key && suppressedTargets.includes(key))
}

export function shouldOfferBrainSuggestion({
  userText,
  priorAssistantText,
  hasUnsavedProposal = false,
} = {}) {
  if (hasUnsavedProposal) return false
  if (!priorAssistantText || !String(priorAssistantText).trim()) return false
  return looksLikeUserFeedback(userText)
}
