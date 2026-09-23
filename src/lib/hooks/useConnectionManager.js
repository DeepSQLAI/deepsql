import { useEffect, useCallback, useMemo } from 'react'
import { useConnections } from './queries'
import { useConnectionId, useDashboardActions } from '@/lib/stores'
import { connectionAPI } from '@/lib/api/client'
import { queryClient } from '@/lib/queryClient'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Whether this page load has already honoured the user's pinned connection.
 *
 * Module scope, not a ref: this hook is called from a dozen sections, and a
 * per-instance guard would let a section mounted later yank the user back to their
 * pinned connection after they had deliberately switched away. Cleared on sign-out by
 * `resetConnectionPinApplied` so the next user's pin is applied on their first load.
 */
let pinAppliedThisLoad = false

/** Called from the auth reset so a pin is re-applied for whoever signs in next. */
export function resetConnectionPinApplied() {
  pinAppliedThisLoad = false
}

/**
 * Shared connection management hook — handles auto-selection on mount,
 * localStorage persistence, and schema cache warming.
 */
export function useConnectionManager() {
  const { setConnectionId } = useDashboardActions()
  const connectionId = useConnectionId()
  const { data: connectionsData, isLoading, refetch } = useConnections()

  // The user's pinned connection sorts first so every list that renders these — the
  // sidebar switcher included — shows the default at the top. `pinned` is per caller,
  // resolved server-side against the effective user, so "View as" sees the target
  // user's default rather than the admin's. Array.sort is stable, so everything else
  // keeps the order the API returned.
  const connections = useMemo(() => {
    const list = Array.isArray(connectionsData) ? connectionsData : []
    return [...list].sort((a, b) => Number(b.pinned === true) - Number(a.pinned === true))
  }, [connectionsData])

  const selectConnection = useCallback(
    (id) => {
      localStorage.setItem('selectedConnectionId', id)
      setConnectionId(id)
      connectionAPI.warmupConnection(id)
    },
    [setConnectionId]
  )

  // Auto-select on load: pinned wins, then the last connection used, then the first
  // available.
  //
  // The pin has to beat an *already selected* connection, not just an empty one:
  // `useDashboardStore` persists `connectionId`, so after a reload something is always
  // selected and a pin that only ran on a blank slate would never actually be the
  // default — which is the whole feature. It applies once per page load
  // (`pinAppliedThisLoad`), so switching connections mid-session still sticks.
  //
  // Critical: if `connectionId` is set but NOT in the connections list (e.g. after
  // "View as" switches to a user without access to that connection), clear it and
  // select a valid one. Otherwise the UI keeps a stale connection the effective user
  // cannot access, and chat/agent calls 403.
  useEffect(() => {
    if (connections.length === 0) return

    const pinned = connections.find((c) => c.pinned)

    if (!pinAppliedThisLoad) {
      pinAppliedThisLoad = true
      if (pinned && pinned.id !== connectionId) {
        selectConnection(pinned.id)
        return
      }
    }

    // Validate the selected connection is actually in the current user's list.
    // When impersonation changes, the connection list is for the target user, but
    // connectionId might still be a connection the admin had selected. Clear it
    // so the user must pick from their own list (or gets auto-selected below).
    if (connectionId) {
      const stillValid = connections.some((c) => c.id === connectionId)
      if (stillValid) return
      // The selected connection is not in the list — clear it and fall through
      // to auto-select from the valid options.
      localStorage.removeItem('selectedConnectionId')
      setConnectionId(null)
    }

    const savedId = localStorage.getItem('selectedConnectionId')
    const saved = savedId ? connections.find((c) => c.id === savedId) : null
    selectConnection((pinned || saved || connections[0]).id)
  }, [connections, connectionId, selectConnection, setConnectionId])

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
