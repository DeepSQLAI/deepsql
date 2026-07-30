/**
 * TanStack Query hooks - Re-exports
 */

// Generic query utilities
export {
  useConnectionQuery,
  useApiMutation,
  useOptimisticMutation,
  useSetQueryData,
  useInvalidateQueries,
  usePrefetchQuery,
} from './useApiQuery'

// Polling hooks
export {
  usePollingQuery,
  useActiveQueryPolling,
  useMonitoringPolling,
} from './usePollingQuery'

// Streaming hooks
export {
  useStreamingQuery,
  useTrainingStream,
} from './useStreamingQuery'

// Parallel queries
export {
  useParallelQueries,
  useConnectionParallelQueries,
  useDependentQueries,
} from './useParallelQueries'
