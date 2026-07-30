import { useState, useEffect, useRef, useCallback } from 'react'
import {
    useTrainingStatus,
    useTrainingHistory,
    useStartSchemaTraining,
    useCancelSchemaTraining,
    getTrainingStreamUrl,
} from '@/lib/hooks/queries'
import { useQueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Custom hook to manage training status with streaming/polling
 * Migrated to TanStack Query with streaming support
 *
 * @deprecated Consider using hooks directly from @/lib/hooks/queries
 */
export function useBrainTraining(connectionId) {
    const queryClient = useQueryClient()
    const [isStreaming, setIsStreaming] = useState(false)
    const pollerRef = useRef(null)
    const streamRef = useRef(null)

    // Queries
    const {
        data: status,
        isLoading: statusLoading,
        refetch: refetchStatus,
    } = useTrainingStatus(connectionId)

    const {
        data: history = [],
        refetch: refetchHistory,
    } = useTrainingHistory(connectionId, 10)

    // Mutations
    const startTrainingMutation = useStartSchemaTraining()
    const cancelTrainingMutation = useCancelSchemaTraining()

    const loading = startTrainingMutation.isPending

    // Stop poller
    const stopPoller = useCallback(() => {
        if (pollerRef.current) {
            clearInterval(pollerRef.current)
            pollerRef.current = null
        }
    }, [])

    // Start poller
    const startPoller = useCallback(() => {
        if (pollerRef.current || !connectionId) {
            return
        }

        pollerRef.current = setInterval(async () => {
            try {
                await refetchStatus()
                const currentStatus = queryClient.getQueryData(queryKeys.training.status(connectionId))
                if (currentStatus?.status !== 'RUNNING' && currentStatus?.status !== 'PENDING') {
                    stopPoller()
                    refetchHistory()
                }
            } catch (err) {
                console.error('Failed to poll training status:', err)
                stopPoller()
            }
        }, 4000)
    }, [connectionId, refetchStatus, refetchHistory, stopPoller, queryClient])

    // Stop stream
    const stopStream = useCallback(() => {
        if (streamRef.current) {
            streamRef.current.close()
            streamRef.current = null
            setIsStreaming(false)
        }
    }, [])

    // Start stream
    const startStream = useCallback(() => {
        if (streamRef.current || !connectionId) {
            return
        }

        const streamUrl = getTrainingStreamUrl(connectionId)
        const eventSource = new EventSource(streamUrl, { withCredentials: true })

        eventSource.addEventListener('trainingStatus', (event) => {
            try {
                const next = JSON.parse(event.data)
                // Update query cache directly
                queryClient.setQueryData(queryKeys.training.status(connectionId), next)

                if (next.status !== 'RUNNING' && next.status !== 'PENDING') {
                    refetchHistory()
                }
            } catch (err) {
                console.error('Failed to parse training status event:', err)
            }
        })

        eventSource.onerror = () => {
            eventSource.close()
            streamRef.current = null
            setIsStreaming(false)
            startPoller()
        }

        streamRef.current = eventSource
        setIsStreaming(true)
        stopPoller()
    }, [connectionId, stopPoller, startPoller, refetchHistory, queryClient])

    // Train function
    const train = useCallback(async () => {
        if (!connectionId) {
            return
        }

        try {
            await startTrainingMutation.mutateAsync(connectionId)

            if (typeof window !== 'undefined' && 'EventSource' in window) {
                startStream()
            } else {
                startPoller()
            }
        } catch (err) {
            console.error('Failed to start training:', err)
        }
    }, [connectionId, startTrainingMutation, startStream, startPoller])

    // Cancel function
    const cancel = useCallback(async () => {
        if (!connectionId) {
            return
        }

        try {
            // Immediately stop stream/poller to prevent status overwrites
            stopStream()
            stopPoller()

            // Set temporary cancelling status
            queryClient.setQueryData(queryKeys.training.status(connectionId), (prev) => ({
                ...prev,
                status: 'CANCELLING',
                message: 'Cancellation requested...'
            }))

            // Request cancellation from backend
            await cancelTrainingMutation.mutateAsync(connectionId)

            // Refresh history to show cancelled run
            refetchHistory()

            // Do one final status check after a short delay
            setTimeout(() => {
                refetchStatus()
            }, 2000)
        } catch (err) {
            console.error('Failed to cancel training:', err)
            queryClient.setQueryData(queryKeys.training.status(connectionId), (prev) => ({
                ...prev,
                status: 'FAILED',
                error: 'Failed to cancel training',
                message: err.message
            }))
        }
    }, [connectionId, cancelTrainingMutation, refetchHistory, refetchStatus, stopStream, stopPoller, queryClient])

    // Effect to manage stream/poller on connectionId change
    useEffect(() => {
        if (!connectionId) {
            stopPoller()
            stopStream()
            return
        }

        // Check if training is running and start appropriate watcher
        if (status?.status === 'RUNNING' || status?.status === 'PENDING') {
            if (typeof window !== 'undefined' && 'EventSource' in window) {
                startStream()
            } else {
                startPoller()
            }
        }

        return () => {
            stopPoller()
            stopStream()
        }
    }, [connectionId, status?.status, startStream, startPoller, stopPoller, stopStream])

    return {
        status,
        history,
        loading: loading || statusLoading,
        train,
        cancel,
        refresh: refetchStatus,
        refreshHistory: refetchHistory
    }
}
