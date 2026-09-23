import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import { useAuth } from '@/hooks/useAuth'
import AgentChatPanel from '@/components/AgentChat/AgentChatPanel'

export default function AgentChatSection() {
  const { connectionId, selectedConnection, isLoading } = useConnectionManager()
  const { username } = useAuth()

  // Wait for the connection list to load before rendering anything. Without this,
  // we might render the agent panel with a stale connectionId from before an
  // impersonation change — the new user's connection list hasn't loaded yet, so
  // selectedConnection is undefined, but connectionId is still the old value.
  if (isLoading) {
    return (
      <div style={{ padding: 40, color: '#6b7280', fontSize: 14 }}>
        Loading connections…
      </div>
    )
  }

  // Either no connection selected, or the selected connectionId is not in the
  // current user's connections (stale after impersonation). Both cases mean the
  // user needs to pick a valid connection before chatting.
  if (!connectionId || !selectedConnection) {
    return (
      <div style={{ padding: 40, color: '#6b7280', fontSize: 14 }}>
        Select a database connection to chat with the DeepSQL Agent.
      </div>
    )
  }

  // Remount on connection *or* identity change so View as re-bootstraps the
  // target user's agent profile instead of keeping the admin MCP session.
  return (
    <AgentChatPanel
      key={`${username || 'anon'}:${connectionId}`}
      connectionId={connectionId}
      connectionName={selectedConnection.connectionName}
      canManageContent={Boolean(selectedConnection.canManageContent)}
    />
  )
}
