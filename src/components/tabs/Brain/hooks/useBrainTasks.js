import { useBrainTasks as useBrainTasksQuery } from '@/lib/hooks/queries'

/**
 * Custom hook to manage brain tasks
 * Migrated to TanStack Query
 *
 * @deprecated Consider using useBrainTasks directly from @/lib/hooks/queries
 */
export function useBrainTasks(connectionId) {
    const {
        data: tasks = [],
        isLoading: loading,
        error: queryError,
        refetch,
    } = useBrainTasksQuery(connectionId, 'OPEN')

    const error = queryError?.message || null

    const refresh = () => {
        refetch()
    }

    return {
        tasks,
        loading,
        error,
        refresh
    }
}
