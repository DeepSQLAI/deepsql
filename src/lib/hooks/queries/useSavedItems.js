/**
 * TanStack Query hooks for Saved Queries and Saved Dashboards APIs
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { savedQueriesAPI, savedDashboardsAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

// ==================== Saved Queries ====================

/**
 * Fetch saved queries for a connection
 */
export function useSavedQueries(connectionId) {
  return useQuery({
    queryKey: queryKeys.savedQueries.all(connectionId),
    queryFn: () => savedQueriesAPI.getQueriesByConnection(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch a specific saved query
 */
export function useSavedQuery(id) {
  return useQuery({
    queryKey: queryKeys.savedQueries.detail(id),
    queryFn: () => savedQueriesAPI.getQueryById(id),
    enabled: Boolean(id),
  })
}

/**
 * Fetch favorite queries
 */
export function useFavoriteQueries(connectionId) {
  return useQuery({
    queryKey: queryKeys.savedQueries.favorites(connectionId),
    queryFn: () => savedQueriesAPI.getFavoriteQueries(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch queries by folder
 */
export function useQueriesByFolder(connectionId, folder) {
  return useQuery({
    queryKey: queryKeys.savedQueries.byFolder(connectionId, folder),
    queryFn: () => savedQueriesAPI.getQueriesByFolder(connectionId, folder),
    enabled: Boolean(connectionId) && Boolean(folder),
  })
}

/**
 * Fetch query folders
 */
export function useQueryFolders(connectionId) {
  return useQuery({
    queryKey: queryKeys.savedQueries.folders(connectionId),
    queryFn: () => savedQueriesAPI.getFolders(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Create a saved query
 */
export function useCreateSavedQuery() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: savedQueriesAPI.createQuery,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({
          queryKey: queryKeys.savedQueries.all(data.connectionId),
        })
      }
    },
  })
}

/**
 * Update a saved query
 */
export function useUpdateSavedQuery() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ id, updates }) => savedQueriesAPI.updateQuery(id, updates),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['savedQueries'] })
    },
  })
}

/**
 * Delete a saved query
 */
export function useDeleteSavedQuery() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: savedQueriesAPI.deleteQuery,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['savedQueries'] })
    },
  })
}

/**
 * Toggle query favorite status
 */
export function useToggleQueryFavorite() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: savedQueriesAPI.toggleFavorite,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['savedQueries'] })
    },
  })
}

// ==================== Saved Dashboards ====================

/**
 * Fetch saved dashboards for a connection
 */
export function useSavedDashboards(connectionId) {
  return useQuery({
    queryKey: queryKeys.savedDashboards.all(connectionId),
    queryFn: () => savedDashboardsAPI.getDashboardsByConnection(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch a specific saved dashboard
 */
export function useSavedDashboard(id) {
  return useQuery({
    queryKey: queryKeys.savedDashboards.detail(id),
    queryFn: () => savedDashboardsAPI.getDashboardById(id),
    enabled: Boolean(id),
  })
}

/**
 * Fetch favorite dashboards
 */
export function useFavoriteDashboards(connectionId) {
  return useQuery({
    queryKey: queryKeys.savedDashboards.favorites(connectionId),
    queryFn: () => savedDashboardsAPI.getFavoriteDashboards(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Fetch dashboards by folder
 */
export function useDashboardsByFolder(connectionId, folder) {
  return useQuery({
    queryKey: queryKeys.savedDashboards.byFolder(connectionId, folder),
    queryFn: () => savedDashboardsAPI.getDashboardsByFolder(connectionId, folder),
    enabled: Boolean(connectionId) && Boolean(folder),
  })
}

/**
 * Fetch dashboard folders
 */
export function useDashboardFolders(connectionId) {
  return useQuery({
    queryKey: queryKeys.savedDashboards.folders(connectionId),
    queryFn: () => savedDashboardsAPI.getFolders(connectionId),
    enabled: Boolean(connectionId),
  })
}

/**
 * Create a saved dashboard
 */
export function useCreateSavedDashboard() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: savedDashboardsAPI.createDashboard,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({
          queryKey: queryKeys.savedDashboards.all(data.connectionId),
        })
      }
    },
  })
}

/**
 * Update a saved dashboard
 */
export function useUpdateSavedDashboard() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ id, updates }) => savedDashboardsAPI.updateDashboard(id, updates),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['savedDashboards'] })
    },
  })
}

/**
 * Delete a saved dashboard
 */
export function useDeleteSavedDashboard() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: savedDashboardsAPI.deleteDashboard,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['savedDashboards'] })
    },
  })
}

/**
 * Toggle dashboard favorite status
 */
export function useToggleDashboardFavorite() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: savedDashboardsAPI.toggleFavorite,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['savedDashboards'] })
    },
  })
}
