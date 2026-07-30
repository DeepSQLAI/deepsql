import { useMemo } from 'react'
import { useBrainUnderstanding } from '@/lib/hooks/queries'

/**
 * Custom hook to manage brain understanding data
 * Migrated to TanStack Query - uses useBrainUnderstanding with data normalization
 *
 * @deprecated Consider using useBrainUnderstanding directly from @/lib/hooks/queries
 */
export function useBrainData(connectionId) {
    const {
        data: rawData,
        isLoading: loading,
        error: queryError,
        refetch,
    } = useBrainUnderstanding(connectionId)

    // Normalize tables data (same logic as before)
    const data = useMemo(() => {
        if (!rawData) return null

        const normalizedTables = (rawData?.tables || []).map((table, index) => {
            const tableName = table?.tableName || table?.tableLabel || 'Unknown table'
            const tableLabel = table?.tableLabel || (table?.schemaName ? `${table.schemaName}.${tableName}` : tableName)
            const issues = table?.issues || table?.bcnfIssues || []
            return {
                ...table,
                tableLabel,
                issues,
                _tableKey: table?.tableName || tableLabel || `table-${index}`
            }
        })

        return {
            ...rawData,
            tables: normalizedTables
        }
    }, [rawData])

    const error = queryError?.message || null

    const refresh = () => {
        refetch()
    }

    return {
        data,
        loading,
        error,
        refresh
    }
}
