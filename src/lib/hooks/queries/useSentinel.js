/**
 * TanStack Query hooks for Sentinel Analytics API
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { sentinelAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Fetch death clock data
 */
export function useDeathClock(connectionId) {
  return useQuery({
    queryKey: queryKeys.sentinel.deathClock(connectionId),
    queryFn: () => sentinelAPI.getDeathClock(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch capacity forecasts
 */
export function useForecasts(connectionId, tableName = null) {
  return useQuery({
    queryKey: queryKeys.sentinel.forecasts(connectionId, tableName),
    queryFn: () => sentinelAPI.getForecasts(connectionId, tableName),
    enabled: Boolean(connectionId),
  })
}

/**
 * Generate a new forecast
 */
export function useGenerateForecast() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ connectionId, tableName }) =>
      sentinelAPI.generateForecast(connectionId, tableName),
    onSuccess: (_data, { connectionId }) => {
      queryClient.invalidateQueries({
        queryKey: ['sentinel', connectionId, 'forecasts'],
      })
    },
  })
}

/**
 * Fetch velocity data
 */
export function useVelocity(connectionId, tableName, historicalDays = 30) {
  return useQuery({
    queryKey: queryKeys.sentinel.velocity(connectionId, tableName),
    queryFn: () => sentinelAPI.getVelocity(connectionId, tableName, historicalDays),
    enabled: Boolean(connectionId) && Boolean(tableName),
  })
}

/**
 * Fetch database events
 */
export function useDatabaseEvents(connectionId, days = 30) {
  return useQuery({
    queryKey: queryKeys.sentinel.events(connectionId, days),
    queryFn: () => sentinelAPI.getEvents(connectionId, days),
    enabled: Boolean(connectionId),
  })
}

/**
 * Log a deployment event
 */
export function useLogDeploymentEvent() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: sentinelAPI.logDeploymentEvent,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({
          queryKey: ['sentinel', data.connectionId, 'events'],
        })
      }
    },
  })
}

/**
 * Log a schema change event
 */
export function useLogSchemaChangeEvent() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: sentinelAPI.logSchemaChangeEvent,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({
          queryKey: ['sentinel', data.connectionId, 'events'],
        })
      }
    },
  })
}

/**
 * Fetch recommendations
 */
export function useSentinelRecommendations(connectionId, { status = null, priority = null } = {}) {
  return useQuery({
    queryKey: queryKeys.sentinel.recommendations(connectionId, status, priority),
    queryFn: () => sentinelAPI.getRecommendations(connectionId, status, priority),
    enabled: Boolean(connectionId),
  })
}

/**
 * Update recommendation status
 */
export function useUpdateRecommendationStatus() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ recommendationId, status, updatedBy = 'user' }) =>
      sentinelAPI.updateRecommendationStatus(recommendationId, status, updatedBy),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sentinel'] })
    },
  })
}

/**
 * Fetch Sentinel summary
 */
export function useSentinelSummary(connectionId) {
  return useQuery({
    queryKey: queryKeys.sentinel.summary(connectionId),
    queryFn: () => sentinelAPI.getSummary(connectionId),
    enabled: Boolean(connectionId),
  })
}
