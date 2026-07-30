import { useEffect, useCallback } from 'react'
import { useConnections } from './queries'
import { useConnectionId, useDashboardActions } from '@/lib/stores'
import { connectionAPI } from '@/lib/api/client'
import { queryClient } from '@/lib/queryClient'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Shared connection management hook — handles auto-selection on mount,
 * localStorage persistence, and schema cache warming.
 */
export function useConnectionManager() {
  const { setConnectionId } = useDashboardActions()
  const connectionId = useConnectionId()
  const { data: connectionsData, isLoading, refetch } = useConnections()
  const connections = Array.isArray(connectionsData) ? connectionsData : []

  // Auto-select saved/first connection on mount
  useEffect(() => {
    if (connections.length === 0) return
    const savedId = localStorage.getItem('selectedConnectionId')
    const savedExists = savedId && connections.some((c) => c.id === savedId)
    if (savedExists && !connectionId) {
      setConnectionId(savedId)
      connectionAPI.warmupConnection(savedId)
    } else if (!savedExists && !connectionId) {
      const first = connections[0].id
      localStorage.setItem('selectedConnectionId', first)
      setConnectionId(first)
      connectionAPI.warmupConnection(first)
    }
  }, [connections, connectionId, setConnectionId])

  const changeConnection = useCallback(
    (connId) => {
      if (!connId) {
        localStorage.removeItem('selectedConnectionId')
        setConnectionId(null)
        return
      }

      // Invalidate chat cache for the old connection so stale data is never shown
      if (connectionId && connectionId !== connId) {
        queryClient.removeQueries({ queryKey: queryKeys.chat.all(connectionId) })
      }

      localStorage.setItem('selectedConnectionId', connId)
      setConnectionId(connId)
      connectionAPI.warmupConnection(connId)
    },
    [setConnectionId, connectionId]
  )

  const selectedConnection = connections.find((c) => c.id === connectionId)

  return { connections, connectionId, selectedConnection, isLoading, changeConnection, refetch }
}
