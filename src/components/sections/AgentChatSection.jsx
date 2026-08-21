import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import { useAuth } from '@/hooks/useAuth'
import AgentChatPanel from '@/components/AgentChat/AgentChatPanel'

export default function AgentChatSection() {
  const { connectionId, selectedConnection } = useConnectionManager()
  const { username } = useAuth()

  if (!connectionId) {
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
      connectionName={selectedConnection?.connectionName}
    />
  )
}
