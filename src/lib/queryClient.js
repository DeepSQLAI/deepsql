import { QueryClient } from '@tanstack/react-query'

/**
 * React Query client configuration
 * Used for caching and retry logic across the app
 */
export const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            staleTime: 5 * 60 * 1000, // Data is fresh for 5 minutes
            gcTime: 10 * 60 * 1000, // Garbage collect after 10 minutes (v5: renamed from cacheTime)
            retry: 3, // Retry failed requests 3 times
            retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000), // Exponential backoff
            refetchOnWindowFocus: false, // Don't refetch on window focus
            refetchOnReconnect: true, // Refetch on reconnect
        },
        mutations: {
            retry: 1, // Retry mutations once
        }
    }
})
