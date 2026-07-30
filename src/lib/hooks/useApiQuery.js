/**
 * Generic TanStack Query wrappers for API calls
 *
 * Provides consistent patterns for queries and mutations across the app.
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'

/**
 * Generic query hook with connection-based enabling
 *
 * @param {Object} options
 * @param {Array} options.queryKey - The query key
 * @param {Function} options.queryFn - The query function
 * @param {string|number|null} options.connectionId - Connection ID (query disabled if null)
 * @param {Object} options.options - Additional query options
 */
export function useConnectionQuery({
  queryKey,
  queryFn,
  connectionId,
  options = {},
}) {
  return useQuery({
    queryKey,
    queryFn,
    enabled: Boolean(connectionId) && options.enabled !== false,
    ...options,
  })
}

/**
 * Generic mutation hook with automatic cache invalidation
 *
 * @param {Object} options
 * @param {Function} options.mutationFn - The mutation function
 * @param {Array|Function} options.invalidateKeys - Query keys to invalidate on success
 * @param {Object} options.options - Additional mutation options
 */
export function useApiMutation({
  mutationFn,
  invalidateKeys = [],
  options = {},
}) {
  const queryClient = useQueryClient()
  // Destructure onSuccess separately to prevent override by spread
  const { onSuccess: customOnSuccess, ...restOptions } = options

  return useMutation({
    mutationFn,
    onSuccess: (data, variables, context) => {
      // Invalidate specified queries
      const keys = typeof invalidateKeys === 'function'
        ? invalidateKeys(data, variables)
        : invalidateKeys

      keys.forEach((key) => {
        queryClient.invalidateQueries({ queryKey: key })
      })

      // Call custom onSuccess if provided
      customOnSuccess?.(data, variables, context)
    },
    ...restOptions,
  })
}

/**
 * Optimistic update mutation hook
 *
 * @param {Object} options
 * @param {Function} options.mutationFn - The mutation function
 * @param {Array} options.queryKey - The query key to update optimistically
 * @param {Function} options.updateFn - Function to update cached data optimistically
 * @param {Object} options.options - Additional mutation options
 */
export function useOptimisticMutation({
  mutationFn,
  queryKey,
  updateFn,
  options = {},
}) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn,
    onMutate: async (variables) => {
      // Cancel any outgoing refetches
      await queryClient.cancelQueries({ queryKey })

      // Snapshot the previous value
      const previousData = queryClient.getQueryData(queryKey)

      // Optimistically update
      if (previousData !== undefined) {
        queryClient.setQueryData(queryKey, (old) => updateFn(old, variables))
      }

      // Return context with snapshot
      return { previousData }
    },
    onError: (_error, _variables, context) => {
      // Rollback on error
      if (context?.previousData !== undefined) {
        queryClient.setQueryData(queryKey, context.previousData)
      }
    },
    onSettled: () => {
      // Refetch after mutation
      queryClient.invalidateQueries({ queryKey })
    },
    ...options,
  })
}

/**
 * Hook to manually set query data
 */
export function useSetQueryData() {
  const queryClient = useQueryClient()

  return (queryKey, data) => {
    queryClient.setQueryData(queryKey, data)
  }
}

/**
 * Hook to invalidate queries
 */
export function useInvalidateQueries() {
  const queryClient = useQueryClient()

  return (queryKey, options = {}) => {
    queryClient.invalidateQueries({ queryKey, ...options })
  }
}

/**
 * Hook to prefetch queries
 */
export function usePrefetchQuery() {
  const queryClient = useQueryClient()

  return (queryKey, queryFn, options = {}) => {
    return queryClient.prefetchQuery({
      queryKey,
      queryFn,
      ...options,
    })
  }
}
