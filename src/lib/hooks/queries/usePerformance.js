/**
 * TanStack Query hooks for Performance & Monitoring APIs
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  performanceAPI,
  performanceInsightsAPI,
  queryPerformanceAPI,
  lockAPI,
} from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

// ==================== Performance Metrics ====================

/**
 * Fetch performance metrics
 */
export function usePerformanceMetrics(connectionId) {
  return useQuery({
    queryKey: queryKeys.performance.metrics(connectionId),
    queryFn: () => performanceAPI.getPerformanceMetrics(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch performance dashboard data
 */
export function usePerformanceDashboard(connectionId, days = 30) {
  return useQuery({
    queryKey: queryKeys.performance.dashboard(connectionId, days),
    queryFn: () => performanceAPI.getDashboardData(connectionId, days),
    enabled: Boolean(connectionId),
  })
}

// ==================== Performance Insights ====================

/**
 * Fetch performance snapshots
 */
export function usePerformanceSnapshots(connectionId, hours = 1) {
  return useQuery({
    queryKey: queryKeys.performanceInsights.snapshots(connectionId, hours),
    queryFn: () => performanceInsightsAPI.getSnapshots(connectionId, hours),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch recent snapshots
 */
export function useRecentSnapshots(connectionId, limit = 12) {
  return useQuery({
    queryKey: queryKeys.performanceInsights.recent(connectionId, limit),
    queryFn: () => performanceInsightsAPI.getRecentSnapshots(connectionId, limit),
    enabled: Boolean(connectionId),
  })
}

/**
 * Collect a performance snapshot
 */
export function useCollectSnapshot() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (connectionId) => performanceInsightsAPI.collectSnapshot(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: ['performanceInsights', connectionId],
      })
    },
  })
}

/**
 * Fetch performance summary
 */
export function usePerformanceSummary(connectionId, hours = 1) {
  return useQuery({
    queryKey: queryKeys.performanceInsights.summary(connectionId, hours),
    queryFn: () => performanceInsightsAPI.getSummary(connectionId, hours),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch table usage statistics for heatmap visualization
 */
export function useTableUsage(connectionId) {
  return useQuery({
    queryKey: queryKeys.performanceInsights.tableUsage(connectionId),
    queryFn: () => performanceInsightsAPI.getTableUsage(connectionId),
    enabled: Boolean(connectionId),
    staleTime: 5 * 60 * 1000, // 5 minutes
  })
}

// ==================== Query Performance ====================

/**
 * Fetch tracked queries
 */
export function useTrackedQueries(connectionId) {
  return useQuery({
    queryKey: queryKeys.queryPerformance.queries(connectionId),
    queryFn: () => queryPerformanceAPI.getTrackedQueries(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch query history
 */
export function useQueryHistory(connectionId, queryHash, days = 7) {
  return useQuery({
    queryKey: queryKeys.queryPerformance.history(connectionId, queryHash, days),
    queryFn: () => queryPerformanceAPI.getQueryHistory(connectionId, queryHash, days),
    enabled: Boolean(connectionId) && Boolean(queryHash),
  })
}

/**
 * Fetch performance trend
 */
export function usePerformanceTrend(connectionId, queryHash, days = 7) {
  return useQuery({
    queryKey: queryKeys.queryPerformance.trend(connectionId, queryHash, days),
    queryFn: () => queryPerformanceAPI.getPerformanceTrend(connectionId, queryHash, days),
    enabled: Boolean(connectionId) && Boolean(queryHash),
  })
}

/**
 * Fetch performance regressions
 */
export function usePerformanceRegressions(connectionId, unacknowledgedOnly = false) {
  return useQuery({
    queryKey: queryKeys.queryPerformance.regressions(connectionId, unacknowledgedOnly),
    queryFn: () => queryPerformanceAPI.getRegressions(connectionId, unacknowledgedOnly),
    enabled: Boolean(connectionId),
  })
}

/**
 * Acknowledge a regression
 */
export function useAcknowledgeRegression() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ regressionId, acknowledgedBy = 'user' }) =>
      queryPerformanceAPI.acknowledgeRegression(regressionId, acknowledgedBy),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['queryPerformance'] })
    },
  })
}

/**
 * Resolve a regression
 */
export function useResolveRegression() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ regressionId, resolutionNotes }) =>
      queryPerformanceAPI.resolveRegression(regressionId, resolutionNotes),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['queryPerformance'] })
    },
  })
}

/**
 * Trigger performance analysis
 */
export function useTriggerAnalysis() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (connectionId) => queryPerformanceAPI.triggerAnalysis(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: ['queryPerformance', connectionId],
      })
    },
  })
}

// ==================== Lock Contention ====================

/**
 * Fetch active locks
 */
export function useActiveLocks(connectionId, { autoRefresh = false, interval = 5000 } = {}) {
  return useQuery({
    queryKey: queryKeys.locks.active(connectionId),
    queryFn: () => lockAPI.getActiveLocks(connectionId),
    enabled: Boolean(connectionId),
    refetchInterval: autoRefresh ? interval : false,
    refetchIntervalInBackground: false,
  })
}

/**
 * Fetch lock statistics
 */
export function useLockStatistics(connectionId) {
  return useQuery({
    queryKey: queryKeys.locks.statistics(connectionId),
    queryFn: () => lockAPI.getStatistics(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Detect lock contentions
 */
export function useDetectContentions() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (connectionId) => lockAPI.detectContentions(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.locks.active(connectionId),
      })
    },
  })
}

/**
 * Kill a session (lock)
 */
export function useKillSession() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ connectionId, pid }) => lockAPI.killSession(connectionId, pid),
    onSuccess: (_data, { connectionId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.locks.active(connectionId),
      })
    },
  })
}
