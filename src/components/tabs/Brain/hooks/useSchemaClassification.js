'use client'

import { useCallback } from 'react'
import {
    useSchemaClassification as useSchemaClassificationQuery,
    useTableClassifications,
    useAnalyzeSchemaClassification,
} from '@/lib/hooks/queries'

/**
 * Hook for managing schema classification data
 * Migrated to TanStack Query with mutations
 *
 * @deprecated Consider using hooks directly from @/lib/hooks/queries
 */
export function useSchemaClassification(connectionId) {
    // Query for fetching schema classification
    const {
        data,
        isLoading: loading,
        error: queryError,
        refetch: fetchClassification,
    } = useSchemaClassificationQuery(connectionId)

    // Mutation for analyzing schema
    const analyzeSchemaClassificationMutation = useAnalyzeSchemaClassification()

    const analyzing = analyzeSchemaClassificationMutation.isPending
    const error = queryError?.message ||
        analyzeSchemaClassificationMutation.error?.message ||
        null

    // fetchTables uses useTableClassifications hook but we need to call it imperatively
    // For backward compatibility, we create a wrapper that fetches tables
    const fetchTables = useCallback(async (role = null) => {
        if (!connectionId) return []

        try {
            // Import the API directly for imperative calls
            const { brainAPI } = await import('@/lib/api/client')
            return await brainAPI.getTableClassifications(connectionId, role)
        } catch (err) {
            console.error('Failed to fetch table classifications:', err)
            return []
        }
    }, [connectionId])

    const analyzeSchema = useCallback(async () => {
        if (!connectionId) return

        const result = await analyzeSchemaClassificationMutation.mutateAsync(connectionId)
        return result
    }, [connectionId, analyzeSchemaClassificationMutation])

    return {
        data,
        loading,
        analyzing,
        error,
        fetchClassification,
        fetchTables,
        analyzeSchema
    }
}
