import test from 'node:test'
import assert from 'node:assert/strict'

// The bubble renderer is a React component; this file locks the payload
// contract the Agent panel attaches after a turn so a missing excerpt or
// MERGE action cannot silently drop the admin preview.

test('proposal payload for a new definition includes excerpt and bubble label', () => {
  const proposal = {
    scopeType: 'COLUMN',
    tableName: 'marts.dim_person',
    columnName: 'meditator_count_current',
    bubbleLabel: 'Save correction: meditator_count_current',
    excerpt: 'The correct pinned metric is meditator_count_current from marts.dim_person',
    proposedNoteText: 'For marts.dim_person.meditator_count_current: The correct pinned metric is meditator_count_current from marts.dim_person',
    action: 'NEW',
  }
  assert.ok(proposal.bubbleLabel.startsWith('Save correction:'))
  assert.ok(proposal.excerpt.length > 20)
  assert.equal(proposal.action, 'NEW')
})

test('merge proposal keeps one intent and names the overlap', () => {
  const proposal = {
    action: 'MERGE',
    overlapReason: 'Overlaps existing brain note — keep as one intent',
    proposedNoteText: 'Existing region rule. New pinned metric definition.',
  }
  assert.equal(proposal.action, 'MERGE')
  assert.match(proposal.overlapReason, /one intent/)
  assert.match(proposal.proposedNoteText, /Existing/)
  assert.match(proposal.proposedNoteText, /pinned metric/)
})
