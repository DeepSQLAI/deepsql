/**
 * Polling query hook for auto-refreshing data
 *
 * Used for real-time monitoring features like active queries, locks, etc.
 */

import { useQuery } from '@tanstack/react-query'

/**
 * Query hook with configurable polling interval
 *
 * @param {Object} options
 * @param {Array} options.queryKey - The query key
 * @param {Function} options.queryFn - The query function
 * @param {boolean} options.enabled - Whether the query is enabled
 * @param {boolean} options.autoRefresh - Whether to enable auto-refresh
 * @param {number} options.interval - Polling interval in milliseconds (default: 5000)
 * @param {Object} options.options - Additional query options
 */
export function usePollingQuery({
  queryKey,
  queryFn,
  enabled = true,
  autoRefresh = false,
  interval = 5000,
  options = {},
}) {
  return useQuery({
    queryKey,
    queryFn,
    enabled,
    refetchInterval: autoRefresh ? interval : false,
    refetchIntervalInBackground: false, // Don't poll when tab is not visible
    ...options,
  })
}

/**
 * Active queries polling hook
 *
 * @param {string|number} connectionId - Connection ID
 * @param {Function} queryFn - The query function
 * @param {boolean} autoRefresh - Whether to enable auto-refresh
 * @param {number} interval - Polling interval (default: 3000ms)
 */
export function useActiveQueryPolling({
  connectionId,
  queryKey,
  queryFn,
  autoRefresh = false,
  interval = 3000,
}) {
  return usePollingQuery({
    queryKey,
    queryFn,
    enabled: Boolean(connectionId),
    autoRefresh,
    interval,
    options: {
      staleTime: autoRefresh ? 0 : 30000, // When polling, always consider data stale
    },
  })
}

/**
 * Monitoring polling hook with longer interval
 *
 * @param {string|number} connectionId - Connection ID
 * @param {Function} queryFn - The query function
 * @param {boolean} autoRefresh - Whether to enable auto-refresh
 */
export function useMonitoringPolling({
  connectionId,
  queryKey,
  queryFn,
  autoRefresh = false,
}) {
  return usePollingQuery({
    queryKey,
    queryFn,
    enabled: Boolean(connectionId),
    autoRefresh,
    interval: 30000, // 30 seconds for monitoring data
  })
}
