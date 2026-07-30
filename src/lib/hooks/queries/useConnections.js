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
