import { QueryClient } from '@tanstack/react-query'

/**
 * Statuses that will never succeed on retry: the request was understood and refused.
 * 401 is excluded on purpose — the axios interceptor refreshes the token and a retry
 * genuinely can succeed after it.
 */
const NON_RETRYABLE_STATUSES = new Set([400, 403, 404, 405, 409, 412, 422])

/**
 * React Query client configuration
 * Used for caching and retry logic across the app
 */
export const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            staleTime: 5 * 60 * 1000, // Data is fresh for 5 minutes
            gcTime: 10 * 60 * 1000, // Garbage collect after 10 minutes (v5: renamed from cacheTime)
            // Retry transient failures only. A 403/404 is a settled answer, and retrying
            // it costs three extra round trips plus ~7s of backoff (1s+2s+4s) before the
            // UI can show anything — observed as four identical requests in the console
            // for one denied call. Some of these are expensive on the server too: an
            // unauthorized /tenant-column-suggestions opens a fresh JDBC connection to
            // the target database on every attempt.
            retry: (failureCount, error) => {
                // `error.status`, not `error.response.status`: the axios response
                // interceptor in api/client.js rethrows a plain Error with the status
                // copied onto it, so `response` is gone by the time react-query sees it.
                // Reading the axios-shaped field looks right and silently never matches —
                // the denied request still made four attempts over ~7s. `response.status`
                // is kept as a fallback for any caller that bypasses the interceptor.
                const status = error?.status ?? error?.response?.status
                if (NON_RETRYABLE_STATUSES.has(status)) return false
                return failureCount < 3
            },
            retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000), // Exponential backoff
            refetchOnWindowFocus: false, // Don't refetch on window focus
            refetchOnReconnect: true, // Refetch on reconnect
        },
        mutations: {
            retry: 1, // Retry mutations once
        }
    }
})
