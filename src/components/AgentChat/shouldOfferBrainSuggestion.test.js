import test from 'node:test'
import assert from 'node:assert/strict'
import { shouldOfferBrainSuggestion, looksLikeUserFeedback, isSuppressedProposal } from './shouldOfferBrainSuggestion.js'

test('clean first-turn answers do not earn a chip', () => {
  assert.equal(shouldOfferBrainSuggestion({
    userText: 'what is the meditator count?',
    priorAssistantText: '',
  }), false)
  assert.equal(shouldOfferBrainSuggestion({
    userText: 'what is the meditator count?',
    priorAssistantText: undefined,
  }), false)
})

test('thanks after a good answer does not earn a chip', () => {
  assert.equal(looksLikeUserFeedback("thanks, that's right"), false)
  assert.equal(shouldOfferBrainSuggestion({
    userText: "thanks, that's right",
    priorAssistantText: 'The pinned metric is meditator_count_current.',
  }), false)
})

test('a correction after a prior answer earns a chip', () => {
  assert.equal(shouldOfferBrainSuggestion({
    userText: "No, that's wrong — use meditator_count_current",
    priorAssistantText: 'There are 12,004 people.',
  }), true)
  assert.equal(shouldOfferBrainSuggestion({
    userText: 'Always filter cancelled bookings on the bookings table',
    priorAssistantText: 'Here are all bookings.',
  }), true)
})

test('do not stack another chip while one is still open', () => {
  assert.equal(shouldOfferBrainSuggestion({
    userText: "No, that's wrong — use meditator_count_current",
    priorAssistantText: 'There are 12,004 people.',
    hasUnsavedProposal: true,
  }), false)
})

test('dismissed targets stay quiet for the rest of the session', () => {
  assert.equal(isSuppressedProposal(
    { tableName: 'marts.dim_person', columnName: 'meditator_count_current' },
    ['marts.dim_person::meditator_count_current']
  ), true)
})
