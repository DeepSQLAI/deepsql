/**
 * Parallel queries hook for fetching multiple resources simultaneously
 *
 * Replaces Promise.all patterns with TanStack Query's useQueries.
 */

import { useQueries, useQuery } from '@tanstack/react-query'

/**
 * Hook to run multiple queries in parallel
 *
 * @param {Array<Object>} queries - Array of query configurations
 * @param {Object} options - Shared options for all queries
 * @returns {Object} Combined result with data, isLoading, isError, etc.
 *
 * @example
 * const results = useParallelQueries([
 *   { queryKey: ['users'], queryFn: fetchUsers },
 *   { queryKey: ['posts'], queryFn: fetchPosts },
 * ])
 *
 * const { data, isLoading, isError } = results
 * const [users, posts] = data // Individual query results
 */
export function useParallelQueries(queries, options = {}) {
  const results = useQueries({
    queries: queries.map((query) => ({
      ...query,
      ...options,
    })),
  })

  // Combine results for easier consumption
  const isLoading = results.some((result) => result.isLoading)
  const isError = results.some((result) => result.isError)
  const isFetching = results.some((result) => result.isFetching)
  const isSuccess = results.every((result) => result.isSuccess)

  const data = results.map((result) => result.data)
  const errors = results.filter((result) => result.error).map((result) => result.error)

  return {
    results, // Individual query results
    data, // Array of data from each query
    errors, // Array of errors (if any)
    isLoading,
    isError,
    isFetching,
    isSuccess,
  }
}

/**
 * Hook to fetch connection-specific data in parallel
 *
 * @param {string|number} connectionId - Connection ID
 * @param {Array<Object>} queries - Array of { key, fn } objects
 * @returns {Object} Combined results
 *
 * @example
 * const { data, isLoading } = useConnectionParallelQueries(connectionId, [
 *   { key: 'stats', fn: () => statsAPI.getStats(connectionId) },
 *   { key: 'schema', fn: () => schemaAPI.getSchema(connectionId) },
 * ])
 *
 * const { stats, schema } = data
 */
export function useConnectionParallelQueries(connectionId, queries) {
  const enabled = Boolean(connectionId)

  const queryConfigs = queries.map(({ key, fn, ...rest }) => ({
    queryKey: [key, connectionId],
    queryFn: fn,
    enabled,
    ...rest,
  }))

  const { results, isLoading, isError, isFetching, isSuccess } = useParallelQueries(queryConfigs)

  // Transform array results into named object
  const data = queries.reduce((acc, { key }, index) => {
    acc[key] = results[index]?.data
    return acc
  }, {})

  return {
    data,
    results,
    isLoading,
    isError,
    isFetching,
    isSuccess,
  }
}

/**
 * Hook for dependent queries where one depends on another
 *
 * @param {Object} options
 * @param {Object} options.primary - Primary query config
 * @param {Function} options.getDependentQueries - Function that returns dependent query configs based on primary data
 */
export function useDependentQueries({ primary, getDependentQueries }) {
  const primaryResult = useQuery(primary)

  const dependentConfigs = primaryResult.data
    ? getDependentQueries(primaryResult.data)
    : []

  const dependentResults = useQueries({
    queries: dependentConfigs.map((config) => ({
      ...config,
      enabled: Boolean(primaryResult.data) && config.enabled !== false,
    })),
  })

  return {
    primary: primaryResult,
    dependents: dependentResults,
    isLoading: primaryResult.isLoading || dependentResults.some((r) => r.isLoading),
    isError: primaryResult.isError || dependentResults.some((r) => r.isError),
  }
}
