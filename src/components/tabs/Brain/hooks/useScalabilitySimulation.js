'use client'

import { useState, useCallback } from 'react'
import {
    useScalabilitySimulations,
    useLatestSimulation,
    useTablePredictions,
    useRunScalabilitySimulation,
    useHighRiskTables,
} from '@/lib/hooks/queries'

/**
 * Hook for managing scalability simulation data
 * Migrated to TanStack Query with mutations
 *
 * @deprecated Consider using hooks directly from @/lib/hooks/queries
 */
export function useScalabilitySimulation(connectionId) {
    const [selectedSimulationId, setSelectedSimulationId] = useState(null)

    // Queries
    const {
        data: simulations = [],
        isLoading: simulationsLoading,
        error: simulationsError,
        refetch: fetchSimulations,
    } = useScalabilitySimulations(connectionId)

    const {
        data: latest,
        isLoading: latestLoading,
        error: latestError,
        refetch: fetchLatest,
    } = useLatestSimulation(connectionId)

    const {
        data: predictions = [],
    } = useTablePredictions(selectedSimulationId)

    // Mutation for running simulation
    const runSimulationMutation = useRunScalabilitySimulation()

    const loading = simulationsLoading || latestLoading
    const simulating = runSimulationMutation.isPending
    const error = simulationsError?.message ||
        latestError?.message ||
        runSimulationMutation.error?.message ||
        null

    const fetchPredictions = useCallback(async (simulationId) => {
        if (!simulationId) return []

        setSelectedSimulationId(simulationId)
        // The query will fetch automatically when simulationId changes
        // For immediate return, we need to call the API directly
        try {
            const { brainAPI } = await import('@/lib/api/client')
            const result = await brainAPI.getTablePredictions(simulationId)
            return result
        } catch (err) {
            console.error('Failed to fetch predictions:', err)
            return []
        }
    }, [])

    const runSimulation = useCallback(async (scenario = null) => {
        if (!connectionId) return

        const result = await runSimulationMutation.mutateAsync({ connectionId, scenario })
        return result
    }, [connectionId, runSimulationMutation])

    const fetchHighRisk = useCallback(async () => {
        if (!connectionId) return []

        try {
            const { brainAPI } = await import('@/lib/api/client')
            return await brainAPI.getHighRiskTables(connectionId)
        } catch (err) {
            console.error('Failed to fetch high-risk tables:', err)
            return []
        }
    }, [connectionId])

    return {
        simulations,
        latest,
        predictions,
        loading,
        simulating,
        error,
        fetchSimulations,
        fetchLatest,
        fetchPredictions,
        runSimulation,
        fetchHighRisk
    }
}
