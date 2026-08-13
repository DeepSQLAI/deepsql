import { create } from 'zustand'

export const useCompanyKnowledgeStore = create((set, get) => ({
  linkedTableFilter: '',
  linkedColumnFilter: '',
  setLinkedFilters: ({ linkedTableFilter = '', linkedColumnFilter = '' } = {}) =>
    set({ linkedTableFilter, linkedColumnFilter }),
  clearLinkedFilters: () => set({ linkedTableFilter: '', linkedColumnFilter: '' }),

  // Active tab inside the Brain surface (formerly Company Knowledge).
  // Valid: 'business-rules' | 'schema-context' | 'sources' | 'suggestions' | 'background-jobs'
  // Default 'background-jobs' (rendered as "Initialize") so users land on the
  // enrichment progress view while the Brain is still running. Once init
  // reports COMPLETED, CompanyKnowledgePanel auto-advances to 'schema-context'
  // via setDefaultTab (see userChoseTab below) — there's nothing left to watch
  // on the init tab, and "add context" is the next real step.
  activeTab: 'background-jobs',
  // True once the user has explicitly clicked a tab. Gates the COMPLETED
  // auto-advance so it only fires while the user is still sitting on the
  // untouched default — it must never yank someone back to 'schema-context'
  // after they've deliberately navigated elsewhere (e.g. to review suggestions).
  userChoseTab: false,
  setActiveTab: (tab) => set({ activeTab: tab, userChoseTab: true }),
  // Used only by the one-time COMPLETED auto-advance — does not count as the
  // user "choosing" a tab, so it can only fire once per session.
  setDefaultTab: (tab) => set({ activeTab: tab }),

  // Suggestion-tab status filter.
  suggestionStatusFilter: 'PENDING',
  setSuggestionStatusFilter: (status) => set({ suggestionStatusFilter: status }),

  // Cross-tab handoff: ambiguity items the user ticked in the Unresolved
  // panel that should land in the Code Sources focus textarea.
  pendingFocusFromAmbiguity: '',
  setPendingFocusFromAmbiguity: (text) => set({ pendingFocusFromAmbiguity: text }),
  consumePendingFocus: () => {
    const text = get().pendingFocusFromAmbiguity
    set({ pendingFocusFromAmbiguity: '' })
    return text
  },
}))

export const useLinkedTableFilter = () => useCompanyKnowledgeStore((state) => state.linkedTableFilter)
export const useLinkedColumnFilter = () => useCompanyKnowledgeStore((state) => state.linkedColumnFilter)
