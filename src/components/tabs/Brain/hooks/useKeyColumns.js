import { useState, useCallback, useEffect } from 'react'
import {
    useKeyColumns as useKeyColumnsQuery,
    useAnalyzeKeyColumns,
    useAcknowledgeAntiPattern,
} from '@/lib/hooks/queries'

/**
 * Custom hook to manage key columns analysis data
 * Migrated to TanStack Query with mutations
 *
 * @deprecated Consider using hooks directly from @/lib/hooks/queries
 */
export function useKeyColumns(connectionId) {
    const [params, setParams] = useState({})

    // Query for fetching key columns
    const {
        data,
        isLoading: loading,
        error: queryError,
        refetch,
    } = useKeyColumnsQuery(connectionId, params)

    // Mutations
    const analyzeKeyColumnsMutation = useAnalyzeKeyColumns()
    const acknowledgeAntiPatternMutation = useAcknowledgeAntiPattern()

    const analyzing = analyzeKeyColumnsMutation.isPending
    const error = queryError?.message ||
        analyzeKeyColumnsMutation.error?.message ||
        acknowledgeAntiPatternMutation.error?.message ||
        null

    const fetchKeyColumns = useCallback((newParams = {}) => {
        setParams(newParams)
        // The query will automatically refetch when params change
    }, [])

    // Refetch when params change
    useEffect(() => {
        if (connectionId) {
            refetch()
        }
    }, [params, connectionId, refetch])

    const analyzeKeyColumns = useCallback(async () => {
        if (!connectionId) return

        const result = await analyzeKeyColumnsMutation.mutateAsync(connectionId)
        return result
    }, [connectionId, analyzeKeyColumnsMutation])

    const acknowledgeAntiPattern = useCallback(async (patternId) => {
        await acknowledgeAntiPatternMutation.mutateAsync(patternId)
        // Query will be invalidated automatically by the mutation
    }, [acknowledgeAntiPatternMutation])

    return {
        data,
        loading,
        analyzing,
        error,
        fetchKeyColumns,
        analyzeKeyColumns,
        acknowledgeAntiPattern
    }
}
