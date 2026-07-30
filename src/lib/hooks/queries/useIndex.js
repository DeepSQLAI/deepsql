/**
 * TanStack Query hooks for Index Recommendations API
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { indexAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Fetch index recommendations
 */
export function useIndexRecommendations(connectionId) {
  return useQuery({
    queryKey: queryKeys.index.recommendations(connectionId),
    queryFn: () => indexAPI.getRecommendations(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Generate index recommendations
 */
export function useGenerateIndexRecommendations() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (connectionId) => indexAPI.generateRecommendations(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.index.recommendations(connectionId),
      })
    },
  })
}

/**
 * Apply an index recommendation
 */
export function useApplyIndexRecommendation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: indexAPI.applyRecommendation,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['index'] })
    },
  })
}

/**
 * Dismiss an index recommendation
 */
export function useDismissIndexRecommendation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: indexAPI.dismissRecommendation,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['index'] })
    },
  })
}

/**
 * Delete an index recommendation
 */
export function useDeleteIndexRecommendation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: indexAPI.deleteRecommendation,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['index'] })
    },
  })
}
