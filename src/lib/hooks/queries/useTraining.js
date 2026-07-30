/**
 * TanStack Query hooks for Training (RAG) API
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { trainingAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Fetch training status for a connection
 */
export function useTrainingStatus(connectionId) {
  return useQuery({
    queryKey: queryKeys.training.status(connectionId),
    queryFn: () => trainingAPI.getSchemaTrainingStatus(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Start schema training
 */
export function useStartSchemaTraining() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (connectionId) => trainingAPI.startSchemaTraining(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.training.status(connectionId),
      })
    },
  })
}

/**
 * Cancel schema training
 */
export function useCancelSchemaTraining() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (connectionId) => trainingAPI.cancelSchemaTraining(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.training.status(connectionId),
      })
    },
  })
}

/**
 * Fetch training history for a connection
 */
export function useTrainingHistory(connectionId, limit = 5) {
  return useQuery({
    queryKey: queryKeys.training.history(connectionId),
    queryFn: () => trainingAPI.getTrainingHistory(connectionId, limit),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch training stats for a connection
 */
export function useTrainingStats(connectionId) {
  return useQuery({
    queryKey: queryKeys.training.stats(connectionId),
    queryFn: () => trainingAPI.getTrainingStats(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch queue metrics
 */
export function useQueueMetrics() {
  return useQuery({
    queryKey: queryKeys.training.queueMetrics(),
    queryFn: trainingAPI.getQueueMetrics,
  })
}

/**
 * Add documentation for training
 */
export function useAddDocumentation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: trainingAPI.addDocumentation,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({
          queryKey: queryKeys.training.all(data.connectionId),
        })
      }
    },
  })
}

/**
 * Get the streaming URL for training status
 * This is not a hook, just a utility function
 */
export function getTrainingStreamUrl(connectionId) {
  return trainingAPI.getSchemaTrainingStreamUrl(connectionId)
}
