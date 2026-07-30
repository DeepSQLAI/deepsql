import { useState, useEffect } from 'react'
import { connectionAPI } from '@/lib/api/client'

/**
 * Custom hook to fetch connection name
 * Replaces: connectionName state and loadConnectionName function
 */
export function useConnectionInfo(connectionId) {
    const [connectionName, setConnectionName] = useState('')

    useEffect(() => {
        if (!connectionId) {
            setConnectionName('')
            return
        }

        const loadConnectionName = async () => {
            try {
                const connections = await connectionAPI.getAllConnections()
                const match = connections.find((conn) => conn.id === connectionId)
                setConnectionName(match?.connectionName || '')
            } catch (err) {
                console.error('Failed to load connection name:', err)
            }
        }

        loadConnectionName()
    }, [connectionId])

    return connectionName
}
