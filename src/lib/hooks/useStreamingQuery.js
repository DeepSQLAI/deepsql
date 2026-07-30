/**
 * Streaming query hook for Server-Sent Events (SSE)
 *
 * Used for real-time training status updates and other streaming data.
 */

import { useEffect, useRef, useCallback } from 'react'
import { useQueryClient } from '@tanstack/react-query'

/**
 * Hook to subscribe to SSE and update query cache in real-time
 *
 * @param {Object} options
 * @param {Array} options.queryKey - The query key to update
 * @param {string} options.url - The SSE endpoint URL
 * @param {boolean} options.enabled - Whether to start the SSE connection
 * @param {Function} options.onMessage - Optional callback for each message
 * @param {Function} options.onError - Optional error callback
 * @param {Function} options.onComplete - Optional completion callback
 * @param {Function} options.parseData - Optional function to parse event data (default: JSON.parse)
 */
export function useStreamingQuery({
  queryKey,
  url,
  enabled = true,
  onMessage,
  onError,
  onComplete,
  parseData = JSON.parse,
}) {
  const queryClient = useQueryClient()
  const eventSourceRef = useRef(null)
  const isConnectedRef = useRef(false)

  // Use refs for callbacks to prevent effect re-runs (fixes memory leak)
  const onMessageRef = useRef(onMessage)
  const onErrorRef = useRef(onError)
  const onCompleteRef = useRef(onComplete)
  const parseDataRef = useRef(parseData)

  // Update refs when callbacks change
  useEffect(() => {
    onMessageRef.current = onMessage
    onErrorRef.current = onError
    onCompleteRef.current = onComplete
    parseDataRef.current = parseData
  })

  const cleanup = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close()
      eventSourceRef.current = null
      isConnectedRef.current = false
    }
  }, [])

  useEffect(() => {
    if (!enabled || !url) {
      cleanup()
      return
    }

    // Prevent duplicate connections
    if (isConnectedRef.current) {
      return
    }

    const eventSource = new EventSource(url)
    eventSourceRef.current = eventSource
    isConnectedRef.current = true

    eventSource.onopen = () => {
      // Connection established
    }

    eventSource.onmessage = (event) => {
      try {
        const data = parseDataRef.current(event.data)

        // Update query cache with new data
        queryClient.setQueryData(queryKey, data)

        // Call optional message handler
        onMessageRef.current?.(data)

        // Check for completion
        if (data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'CANCELLED') {
          onCompleteRef.current?.(data)
          cleanup()
        }
      } catch (error) {
        onErrorRef.current?.(error)
      }
    }

    eventSource.onerror = (error) => {
      onErrorRef.current?.(error)
      cleanup()
    }

    return cleanup
  }, [enabled, url, queryKey, queryClient, cleanup])

  return {
    isConnected: enabled && Boolean(url),
    disconnect: cleanup,
  }
}

/**
 * Training status streaming hook
 *
 * @param {string|number} connectionId - Connection ID
 * @param {string} streamUrl - The streaming URL
 * @param {boolean} enabled - Whether to start streaming
 * @param {Object} callbacks - Optional callbacks
 */
export function useTrainingStream({
  connectionId,
  streamUrl,
  enabled = false,
  onProgress,
  onComplete,
  onError,
}) {
  const queryKey = ['training', connectionId, 'status']

  return useStreamingQuery({
    queryKey,
    url: streamUrl,
    enabled: enabled && Boolean(connectionId),
    onMessage: (data) => {
      onProgress?.(data)
    },
    onComplete,
    onError,
  })
}
