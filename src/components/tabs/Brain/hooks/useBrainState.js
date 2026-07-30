import { useReducer } from 'react'

/**
 * Initial state for Brain tab UI
 * Consolidates all the modal, form, filter, and UI state
 */
const initialState = {
    // Modal states
    modals: {
        action: null, // 'train' | 'profile' | 'rescan' | null
        note: null, // { noteId, scopeType, tableName, columnName, reason, locked, ambiguousTables } | null
        ambiguity: null, // { columnName, tableName, ambiguousTables } | null
        bcnf: null, // { tableLabel, bcnfScore, bcnfStatus, issues, suggestions } | null
        bcnfReview: false
    },

    // Form states
    forms: {
        note: {
            scopeType: 'TABLE',
            tableName: '',
            columnName: '',
            noteText: ''
        },
        ambiguity: {
            selection: '',
            filter: '',
            showAll: false
        }
    },

    // Search and filter states
    filters: {
        noteSearch: '',
        tableSearch: '',
        tableScoreFilter: '',
        columnSearch: '',
        columnScoreFilter: '',
        bcnfFilter: 'all',
        bcnfReviewSearch: '',
        ambiguousOnly: false,
        showAmbiguousDocTables: false
    },

    // Loading states
    loading: {
        rescan: false,
        profile: false,
        noteSaving: false,
        ambiguitySaving: false
    },

    // Result states
    results: {
        profile: null,
        noteAction: null,
        bcnfTask: null
    },

    // Selected items
    selected: {
        historyEntry: null
    }
}

/**
 * Actions for the reducer
 */
export const ACTIONS = {
    // Modal actions
    OPEN_MODAL: 'OPEN_MODAL',
    CLOSE_MODAL: 'CLOSE_MODAL',
    CLOSE_ALL_MODALS: 'CLOSE_ALL_MODALS',

    // Form actions
    UPDATE_NOTE_FORM: 'UPDATE_NOTE_FORM',
    RESET_NOTE_FORM: 'RESET_NOTE_FORM',
    UPDATE_AMBIGUITY_FORM: 'UPDATE_AMBIGUITY_FORM',
    RESET_AMBIGUITY_FORM: 'RESET_AMBIGUITY_FORM',

    // Filter actions
    UPDATE_FILTER: 'UPDATE_FILTER',
    RESET_FILTERS: 'RESET_FILTERS',

    // Loading actions
    SET_LOADING: 'SET_LOADING',

    // Result actions
    SET_RESULT: 'SET_RESULT',
    CLEAR_RESULT: 'CLEAR_RESULT',

    // Selection actions
    SELECT_HISTORY_ENTRY: 'SELECT_HISTORY_ENTRY',

    // Reset all
    RESET_ALL: 'RESET_ALL'
}

/**
 * Reducer function
 */
function brainReducer(state, action) {
    switch (action.type) {
        case ACTIONS.OPEN_MODAL:
            return {
                ...state,
                modals: {
                    ...state.modals,
                    [action.payload.modal]: action.payload.data
                }
            }

        case ACTIONS.CLOSE_MODAL:
            return {
                ...state,
                modals: {
                    ...state.modals,
                    [action.payload.modal]: action.payload.modal === 'bcnfReview' ? false : null
                }
            }

        case ACTIONS.CLOSE_ALL_MODALS:
            return {
                ...state,
                modals: initialState.modals
            }

        case ACTIONS.UPDATE_NOTE_FORM:
            return {
                ...state,
                forms: {
                    ...state.forms,
                    note: {
                        ...state.forms.note,
                        ...action.payload
                    }
                }
            }

        case ACTIONS.RESET_NOTE_FORM:
            return {
                ...state,
                forms: {
                    ...state.forms,
                    note: initialState.forms.note
                }
            }

        case ACTIONS.UPDATE_AMBIGUITY_FORM:
            return {
                ...state,
                forms: {
                    ...state.forms,
                    ambiguity: {
                        ...state.forms.ambiguity,
                        ...action.payload
                    }
                }
            }

        case ACTIONS.RESET_AMBIGUITY_FORM:
            return {
                ...state,
                forms: {
                    ...state.forms,
                    ambiguity: initialState.forms.ambiguity
                }
            }

        case ACTIONS.UPDATE_FILTER:
            return {
                ...state,
                filters: {
                    ...state.filters,
                    [action.payload.filter]: action.payload.value
                }
            }

        case ACTIONS.RESET_FILTERS:
            return {
                ...state,
                filters: initialState.filters
            }

        case ACTIONS.SET_LOADING:
            return {
                ...state,
                loading: {
                    ...state.loading,
                    [action.payload.key]: action.payload.value
                }
            }

        case ACTIONS.SET_RESULT:
            return {
                ...state,
                results: {
                    ...state.results,
                    [action.payload.key]: action.payload.value
                }
            }

        case ACTIONS.CLEAR_RESULT:
            return {
                ...state,
                results: {
                    ...state.results,
                    [action.payload.key]: null
                }
            }

        case ACTIONS.SELECT_HISTORY_ENTRY:
            return {
                ...state,
                selected: {
                    ...state.selected,
                    historyEntry: action.payload
                }
            }

        case ACTIONS.RESET_ALL:
            return initialState

        default:
            return state
    }
}

/**
 * Custom hook that provides the reducer and helper functions
 */
export function useBrainState() {
    const [state, dispatch] = useReducer(brainReducer, initialState)

    // Helper functions for common actions
    const openModal = (modal, data = true) => {
        dispatch({ type: ACTIONS.OPEN_MODAL, payload: { modal, data } })
    }

    const closeModal = (modal) => {
        dispatch({ type: ACTIONS.CLOSE_MODAL, payload: { modal } })
    }

    const closeAllModals = () => {
        dispatch({ type: ACTIONS.CLOSE_ALL_MODALS })
    }

    const updateNoteForm = (updates) => {
        dispatch({ type: ACTIONS.UPDATE_NOTE_FORM, payload: updates })
    }

    const resetNoteForm = () => {
        dispatch({ type: ACTIONS.RESET_NOTE_FORM })
    }

    const updateAmbiguityForm = (updates) => {
        dispatch({ type: ACTIONS.UPDATE_AMBIGUITY_FORM, payload: updates })
    }

    const resetAmbiguityForm = () => {
        dispatch({ type: ACTIONS.RESET_AMBIGUITY_FORM })
    }

    const updateFilter = (filter, value) => {
        dispatch({ type: ACTIONS.UPDATE_FILTER, payload: { filter, value } })
    }

    const resetFilters = () => {
        dispatch({ type: ACTIONS.RESET_FILTERS })
    }

    const setLoading = (key, value) => {
        dispatch({ type: ACTIONS.SET_LOADING, payload: { key, value } })
    }

    const setResult = (key, value) => {
        dispatch({ type: ACTIONS.SET_RESULT, payload: { key, value } })
    }

    const clearResult = (key) => {
        dispatch({ type: ACTIONS.CLEAR_RESULT, payload: { key } })
    }

    const selectHistoryEntry = (entry) => {
        dispatch({ type: ACTIONS.SELECT_HISTORY_ENTRY, payload: entry })
    }

    const resetAll = () => {
        dispatch({ type: ACTIONS.RESET_ALL })
    }

    return {
        state,
        dispatch,
        // Helper functions
        openModal,
        closeModal,
        closeAllModals,
        updateNoteForm,
        resetNoteForm,
        updateAmbiguityForm,
        resetAmbiguityForm,
        updateFilter,
        resetFilters,
        setLoading,
        setResult,
        clearResult,
        selectHistoryEntry,
        resetAll
    }
}
