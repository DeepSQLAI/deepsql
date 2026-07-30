import { useBrainNotes as useBrainNotesQuery } from '@/lib/hooks/queries'

/**
 * Custom hook to manage brain notes
 * Migrated to TanStack Query
 *
 * @deprecated Consider using useBrainNotes directly from @/lib/hooks/queries
 */
export function useBrainNotes(connectionId) {
    const {
        data: notes = [],
        isLoading: loading,
        error: queryError,
        refetch,
    } = useBrainNotesQuery(connectionId)

    const error = queryError?.message || null

    const refresh = () => {
        refetch()
    }

    return {
        notes,
        loading,
        error,
        refresh
    }
}
