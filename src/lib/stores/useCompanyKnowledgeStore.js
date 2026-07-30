import { create } from 'zustand'

export const useCompanyKnowledgeStore = create((set, get) => ({
  linkedTableFilter: '',
  linkedColumnFilter: '',
  setLinkedFilters: ({ linkedTableFilter = '', linkedColumnFilter = '' } = {}) =>
    set({ linkedTableFilter, linkedColumnFilter }),
  clearLinkedFilters: () => set({ linkedTableFilter: '', linkedColumnFilter: '' }),

  // Active tab inside the Brain surface (formerly Company Knowledge).
  // Valid: 'business-rules' | 'schema-context' | 'sources' | 'suggestions' | 'background-jobs'
  // Default 'background-jobs' (rendered as "Brain Init") so users land on
  // the enrichment progress + scheduled-jobs view first — it's the natural
  // "is my brain ready?" entry point. Key kept as 'background-jobs' for
  // backwards compatibility with any persisted state.
  activeTab: 'background-jobs',
  setActiveTab: (tab) => set({ activeTab: tab }),

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
