'use client'

import { useState, useCallback, useEffect } from 'react'
import {
    useQueryAntiPatterns as useQueryAntiPatternsQuery,
    useAnalyzeQueryAntiPatterns,
} from '@/lib/hooks/queries'

/**
 * Hook for managing query anti-patterns data
 * Migrated to TanStack Query with mutations
 *
 * @deprecated Consider using hooks directly from @/lib/hooks/queries
 */
export function useQueryAntiPatterns(connectionId) {
    const [limit, setLimit] = useState(null)

    // Query for fetching patterns
    const {
        data: patterns = [],
        isLoading: loading,
        error: queryError,
        refetch,
    } = useQueryAntiPatternsQuery(connectionId, limit)

    // Mutation for analyzing patterns
    const analyzePatternsMutation = useAnalyzeQueryAntiPatterns()

    const analyzing = analyzePatternsMutation.isPending
    const error = queryError?.message ||
        analyzePatternsMutation.error?.message ||
        null

    const fetchPatterns = useCallback((newLimit = null) => {
        setLimit(newLimit)
    }, [])

    // Refetch when limit changes
    useEffect(() => {
        if (connectionId) {
            refetch()
        }
    }, [limit, connectionId, refetch])

    const analyzePatterns = useCallback(async () => {
        if (!connectionId) return

        const result = await analyzePatternsMutation.mutateAsync(connectionId)
        return result
    }, [connectionId, analyzePatternsMutation])

    return {
        patterns,
        loading,
        analyzing,
        error,
        fetchPatterns,
        analyzePatterns,
        refetchPatterns: refetch,
    }
}
