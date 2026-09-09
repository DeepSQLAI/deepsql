/**
 * TanStack Query hooks for Connection API
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { connectionAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Fetch all connections
 */
export function useConnections() {
  return useQuery({
    queryKey: queryKeys.connections.all,
    queryFn: connectionAPI.getAllConnections,
  })
}

/**
 * Test a database connection
 */
export function useTestConnection() {
  return useMutation({
    mutationFn: connectionAPI.testConnection,
  })
}

/**
 * Save a new connection
 */
export function useSaveConnection() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: connectionAPI.saveConnection,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.connections.all })
    },
  })
}

/**
 * Update an existing connection
 */
export function useUpdateConnection() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ connectionId, connectionData }) =>
      connectionAPI.updateConnection(connectionId, connectionData),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.connections.all })
    },
  })
}

/**
 * Delete a connection
 */
export function useDeleteConnection() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: connectionAPI.deleteConnection,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.connections.all })
    },
  })
}

/**
 * Pin or unpin a connection as this user's default.
 *
 * The `pinned` flag rides the connection list response, so invalidating that one key
 * updates every surface that shows connections — the sidebar switcher included —
 * without a second request.
 */
export function useSetConnectionPin() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ connectionId, pinned }) =>
      pinned
        ? connectionAPI.pinConnection(connectionId)
        : connectionAPI.unpinConnection(connectionId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.connections.all })
    },
  })
}
