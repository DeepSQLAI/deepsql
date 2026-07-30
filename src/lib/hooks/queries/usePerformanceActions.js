import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { performanceActionsAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Hook to fetch all pending performance actions sorted by ROI.
 */
export function usePerformanceActions(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.performanceActions.list(connectionId),
    queryFn: () => performanceActionsAPI.getActions(connectionId),
    enabled: !!connectionId,
    staleTime: 2 * 60 * 1000, // 2 minutes
    ...options,
  })
}

/**
 * Hook to fetch top N actions by ROI.
 */
export function useTopPerformanceActions(connectionId, limit = 10, options = {}) {
  return useQuery({
    queryKey: queryKeys.performanceActions.top(connectionId, limit),
    queryFn: () => performanceActionsAPI.getTopActions(connectionId, limit),
    enabled: !!connectionId,
    staleTime: 2 * 60 * 1000,
    ...options,
  })
}

/**
 * Hook to fetch actions by category.
 */
export function usePerformanceActionsByCategory(connectionId, category, options = {}) {
  return useQuery({
    queryKey: queryKeys.performanceActions.byCategory(connectionId, category),
    queryFn: () => performanceActionsAPI.getActionsByCategory(connectionId, category),
    enabled: !!connectionId && !!category,
    staleTime: 2 * 60 * 1000,
    ...options,
  })
}

/**
 * Hook to fetch actions by source.
 */
export function usePerformanceActionsBySource(connectionId, source, options = {}) {
  return useQuery({
    queryKey: queryKeys.performanceActions.bySource(connectionId, source),
    queryFn: () => performanceActionsAPI.getActionsBySource(connectionId, source),
    enabled: !!connectionId && !!source,
    staleTime: 2 * 60 * 1000,
    ...options,
  })
}

/**
 * Hook to fetch action summary statistics.
 */
export function usePerformanceActionsSummary(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.performanceActions.summary(connectionId),
    queryFn: () => performanceActionsAPI.getSummary(connectionId),
    enabled: !!connectionId,
    staleTime: 2 * 60 * 1000,
    ...options,
  })
}

/**
 * Hook to refresh actions from all sources.
 */
export function useRefreshPerformanceActions() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (connectionId) => performanceActionsAPI.refreshActions(connectionId),
    onSuccess: (data, connectionId) => {
      // Invalidate all performance action queries for this connection
      queryClient.invalidateQueries({
        queryKey: queryKeys.performanceActions.all(connectionId),
      })
    },
  })
}

/**
 * Hook to update action status.
 */
export function useUpdateActionStatus() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ actionId, status, resolvedBy, notes }) =>
      performanceActionsAPI.updateStatus(actionId, status, resolvedBy, notes),
    onSuccess: (data) => {
      // Invalidate all performance action queries for this connection
      queryClient.invalidateQueries({
        queryKey: queryKeys.performanceActions.all(data.connectionId),
      })
    },
  })
}

/**
 * Hook to batch update action statuses.
 */
export function useBatchUpdateActionStatus() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ actionIds, status, resolvedBy, notes }) =>
      performanceActionsAPI.batchUpdateStatus(actionIds, status, resolvedBy, notes),
    onSuccess: (data) => {
      // Invalidate all performance action queries
      if (data && data.length > 0) {
        queryClient.invalidateQueries({
          queryKey: queryKeys.performanceActions.all(data[0].connectionId),
        })
      }
    },
  })
}
