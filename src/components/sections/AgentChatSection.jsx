import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import AgentChatPanel from '@/components/AgentChat/AgentChatPanel'

export default function AgentChatSection() {
  const { connectionId, selectedConnection } = useConnectionManager()

  if (!connectionId) {
    return (
      <div style={{ padding: 40, color: '#6b7280', fontSize: 14 }}>
        Select a database connection to chat with the DeepSQL Agent.
      </div>
    )
  }

  // Remount on connection change so the chat re-bootstraps a fresh session.
  return (
    <AgentChatPanel
      key={connectionId}
      connectionId={connectionId}
      connectionName={selectedConnection?.connectionName}
    />
  )
}
