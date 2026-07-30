import { useState, useCallback, useEffect } from 'react'
import { brainAPI } from '@/lib/api/client'

/**
 * Custom hook to manage column values data
 * Displays cached low-cardinality column values used for chat context
 */
export function useColumnValues(connectionId) {
    const [data, setData] = useState([])
    const [stats, setStats] = useState(null)
    const [loading, setLoading] = useState(false)
    const [refreshing, setRefreshing] = useState(false)
    const [error, setError] = useState(null)

    const fetchColumnValues = useCallback(async () => {
        if (!connectionId) return

        setLoading(true)
        setError(null)

        try {
            const [valuesResponse, statsResponse] = await Promise.all([
                brainAPI.getColumnValues(connectionId),
                brainAPI.getColumnValuesStats(connectionId)
            ])
            setData(valuesResponse || [])
            setStats(statsResponse || null)
        } catch (err) {
            console.error('Failed to fetch column values:', err)
            setError(err.message || 'Failed to fetch column values')
        } finally {
            setLoading(false)
        }
    }, [connectionId])

    const refreshColumnValues = useCallback(async () => {
        if (!connectionId) return

        setRefreshing(true)
        setError(null)

        try {
            await brainAPI.refreshColumnValues(connectionId)
            // Wait a bit for the async process to start collecting
            await new Promise(resolve => setTimeout(resolve, 2000))
            // Refetch to show any new values
            await fetchColumnValues()
        } catch (err) {
            console.error('Failed to refresh column values:', err)
            setError(err.message || 'Failed to refresh column values')
        } finally {
            setRefreshing(false)
        }
    }, [connectionId, fetchColumnValues])

    const embedAllValues = useCallback(async () => {
        setRefreshing(true)
        setError(null)

        try {
            const result = await brainAPI.embedAllColumnValues()
            // Refetch to show updated embedding status
            await fetchColumnValues()
            return result
        } catch (err) {
            console.error('Failed to embed column values:', err)
            setError(err.message || 'Failed to embed column values')
        } finally {
            setRefreshing(false)
        }
    }, [fetchColumnValues])

    useEffect(() => {
        if (connectionId) {
            fetchColumnValues()
        }
    }, [connectionId, fetchColumnValues])

    return {
        data,
        stats,
        loading,
        refreshing,
        error,
        fetchColumnValues,
        refreshColumnValues,
        embedAllValues
    }
}
