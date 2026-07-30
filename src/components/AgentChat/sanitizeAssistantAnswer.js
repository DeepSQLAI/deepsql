/**
 * Strip calculation / grounding footnotes from agent answers so the bubble
 * shows the result (and an optional follow-up) without "how we computed it".
 * Tool steps already surface that detail in the collapsible activity list.
 */
const CALC_HEADING =
  /^(Used|Grounding used|Filters applied|How (I|we) (got|calculated|computed)( this| it| the (answer|result))?|Evidence|Assumption|Supporting evidence|Verification notes|Tables used|Joins used|SQL used|Query used|Logic used|Mapping used)\s*:?\s*$/i

const CALC_INLINE_PREFIX =
  /^(Used|Grounding used|Filters applied|How (I|we) (got|calculated|computed))\s*:/i

export function sanitizeAssistantAnswer(content) {
  if (!content || typeof content !== 'string') return content

  const lines = content.split('\n')
  let cutoff = -1

  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trim().replace(/^\*+|\*+$/g, '').trim()
    if (CALC_HEADING.test(trimmed) || CALC_INLINE_PREFIX.test(trimmed)) {
      cutoff = i
      break
    }
  }

  if (cutoff === -1) {
    return content.replace(/\n{3,}/g, '\n\n').trim()
  }

  // Drop a short trailing "code → label" mapping line immediately above "Used:"
  // (e.g. "`LK` `Sri Lanka`") — it's part of the calculation footnote, not the answer.
  let start = cutoff
  if (start > 0) {
    const prev = lines[start - 1].trim()
    if (/^(`[^`]+`\s*){1,4}$/.test(prev) || /^[A-Z]{2}\s+[A-Za-z].{0,40}$/.test(prev)) {
      start -= 1
      if (start > 0 && !lines[start - 1].trim()) start -= 1
    }
  }

  return lines
    .slice(0, start)
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}
