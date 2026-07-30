/**
 * TanStack Query hooks for Active Queries API
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { activeQueriesAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Fetch active queries with optional polling
 *
 * @param {string|number} connectionId - Connection ID
 * @param {Object} options
 * @param {boolean} options.autoRefresh - Enable auto-refresh polling
 * @param {number} options.interval - Polling interval in ms (default: 3000)
 */
export function useActiveQueries(connectionId, { autoRefresh = false, interval = 3000 } = {}) {
  return useQuery({
    queryKey: queryKeys.activeQueries.list(connectionId),
    queryFn: () => activeQueriesAPI.getActiveQueries(connectionId),
    enabled: Boolean(connectionId),
    refetchInterval: autoRefresh ? interval : false,
    refetchIntervalInBackground: false,
    staleTime: autoRefresh ? 0 : 30000,
  })
}

/**
 * Fetch latest queries
 */
export function useLatestQueries(connectionId) {
  return useQuery({
    queryKey: queryKeys.activeQueries.latest(connectionId),
    queryFn: () => activeQueriesAPI.getLatestQueries(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch query statistics
 */
export function useActiveQueryStatistics(connectionId) {
  return useQuery({
    queryKey: queryKeys.activeQueries.statistics(connectionId),
    queryFn: () => activeQueriesAPI.getStatistics(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch filter options for active queries
 */
export function useActiveQueryFilterOptions(connectionId) {
  return useQuery({
    queryKey: queryKeys.activeQueries.filterOptions(connectionId),
    queryFn: () => activeQueriesAPI.getFilterOptions(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Capture queries manually
 */
export function useCaptureQueries() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (connectionId) => activeQueriesAPI.captureQueries(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.activeQueries.all(connectionId),
      })
    },
  })
}

/**
 * Kill a query by PID
 */
export function useKillQuery() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ connectionId, pid }) => activeQueriesAPI.killQuery(connectionId, pid),
    onSuccess: (_data, { connectionId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.activeQueries.all(connectionId),
      })
    },
  })
}
