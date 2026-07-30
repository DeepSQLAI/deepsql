import { useEffect, useRef, useCallback, useState } from 'react'

/**
 * Custom hook for WebSocket connections with automatic reconnection
 * Replaces polling for real-time updates
 */
export function useWebSocket(url, options = {}) {
    const {
        enabled = true,
        reconnectInterval = 3000,
        maxReconnectAttempts = 5,
        onMessage = () => {},
        onError = () => {},
        onConnect = () => {},
        onDisconnect = () => {}
    } = options

    const [isConnected, setIsConnected] = useState(false)
    const [reconnectCount, setReconnectCount] = useState(0)
    const wsRef = useRef(null)
    const reconnectTimeoutRef = useRef(null)

    const connect = useCallback(() => {
        if (!enabled || !url) return

        try {
            const ws = new WebSocket(url)

            ws.onopen = () => {
                console.log('WebSocket connected:', url)
                setIsConnected(true)
                setReconnectCount(0)
                onConnect()
            }

            ws.onmessage = (event) => {
                try {
                    const data = JSON.parse(event.data)
                    onMessage(data)
                } catch (err) {
                    console.error('Failed to parse WebSocket message:', err)
                }
            }

            ws.onerror = (error) => {
                console.error('WebSocket error:', error)
                onError(error)
            }

            ws.onclose = () => {
                console.log('WebSocket disconnected')
                setIsConnected(false)
                wsRef.current = null
                onDisconnect()

                // Attempt reconnection
                if (reconnectCount < maxReconnectAttempts) {
                    console.log(`Reconnecting in ${reconnectInterval}ms (attempt ${reconnectCount + 1}/${maxReconnectAttempts})`)
                    reconnectTimeoutRef.current = setTimeout(() => {
                        setReconnectCount(prev => prev + 1)
                        connect()
                    }, reconnectInterval)
                }
            }

            wsRef.current = ws
        } catch (err) {
            console.error('Failed to create WebSocket connection:', err)
            onError(err)
        }
    }, [url, enabled, reconnectCount, maxReconnectAttempts, reconnectInterval, onMessage, onError, onConnect, onDisconnect])

    const disconnect = useCallback(() => {
        if (reconnectTimeoutRef.current) {
            clearTimeout(reconnectTimeoutRef.current)
            reconnectTimeoutRef.current = null
        }

        if (wsRef.current) {
            wsRef.current.close()
            wsRef.current = null
        }

        setIsConnected(false)
        setReconnectCount(0)
    }, [])

    const send = useCallback((data) => {
        if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
            wsRef.current.send(typeof data === 'string' ? data : JSON.stringify(data))
        } else {
            console.warn('WebSocket is not connected. Cannot send message.')
        }
    }, [])

    useEffect(() => {
        if (enabled && url) {
            connect()
        }

        return () => {
            disconnect()
        }
    }, [enabled, url, connect, disconnect])

    return {
        isConnected,
        send,
        disconnect,
        reconnect: connect
    }
}
