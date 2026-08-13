import { useState, useRef, useEffect, useCallback } from 'react'
import { ArrowUp, Plus, Square, Loader2, Database } from 'lucide-react'
import { agentChatAPI, withConnectionContext } from '@/lib/api/agentClient'
import { agentConversationAPI } from '@/lib/api/client'
import AgentMarkdown from './AgentMarkdown'
import { sanitizeAssistantAnswer } from './sanitizeAssistantAnswer'
import styles from './AgentChatPanel.module.css'

function updateLast(messages, updater) {
  if (!messages.length) return messages
  const copy = messages.slice()
  copy[copy.length - 1] = updater(copy[copy.length - 1])
  return copy
}

function toolLabel(d) {
  const name = (d?.name || '').replace(/^mcp_deepsql_/, '').replace(/^skill_view$/, 'skill')
  if (d?.args?.query) return `SQL · ${String(d.args.query).replace(/\s+/g, ' ').slice(0, 90)}`
  if (d?.args?.name) return `skill · ${d.args.name}`
  return name || 'tool'
}

// Conversations are persisted server-side, keyed by DeepSQL user identity (see
// agentConversationAPI), so a user resumes their chats from any device. The
// rendered transcript is mirrored in our backend; the agent's server-side
// context is resumed by reusing the stored agent session id.
function deriveTitle(messages) {
  const firstUser = messages.find((m) => m.role === 'user')
  if (!firstUser?.content) return null
  return firstUser.content.replace(/\s+/g, ' ').trim().slice(0, 80)
}

// Generic, schema-agnostic prompts — must work for any connection (booking
// systems, SaaS multi-tenant DBs, analytics warehouses, ...). Never hardcode
// a domain-specific table/column name here (see the chat guardrail in
// AGENTS.md); AgentChatPanel has no idea what tables the active connection has.
const SUGGESTIONS = [
  'How many tables are there?',
  'Show the largest tables',
  'What are the top slow queries?',
]

export default function AgentChatPanel({ connectionId, connectionName }) {
  const [sessionId, setSessionId] = useState(null)
  const [booting, setBooting] = useState(true)
  const [bootError, setBootError] = useState(null)
  // True once /api/agent/session comes back with mcpAuthOk===false — the
  // freshly provisioned MCP token can't reach DeepSQL's API. Chat must stay
  // blocked until a retry goes green (W1: fail loud before the first message,
  // not six tool-call failures in).
  const [authBlocked, setAuthBlocked] = useState(false)
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const esRef = useRef(null)
  const streamIdRef = useRef(null)
  const listRef = useRef(null)
  const firstMsgRef = useRef(true)
  const profileRef = useRef(null)   // resolved agent profile (for new sessions)
  const convIdRef = useRef(null)    // backend conversation id (the per-user index row)
  const restoredRef = useRef(false) // guards the persist effect until boot finishes

  const boot = useCallback(async ({ fresh = false } = {}) => {
    setBooting(true); setBootError(null); setAuthBlocked(false)
    restoredRef.current = false
    convIdRef.current = null
    esRef.current?.close(); esRef.current = null
    try {
      const { profile, mcpAuthOk, mcpAuthError } = await agentChatAPI.bootstrap(connectionId)
      if (mcpAuthOk === false) {
        setAuthBlocked(true)
        setBootError(mcpAuthError || 'Agent cannot reach DeepSQL (auth). Reconnect / check Agent runtime.')
        setBooting(false)
        return
      }
      profileRef.current = profile
      // Hermes requires the hermes_profile cookie before session/chat calls;
      // without it, chat/start 404s and the UI loader never resolves.
      await agentChatAPI.switchProfile(profile)

      // Resume the user's most recent conversation for this connection (from our
      // identity-keyed backend, so it works on any device) unless they asked for
      // a new chat. Restore the mirrored transcript and reuse the stored agent
      // session id so the agent keeps its server-side context.
      if (!fresh) {
        try {
          const list = await agentConversationAPI.list(connectionId)
          if (Array.isArray(list) && list.length) {
            const conv = await agentConversationAPI.get(list[0].id)
            const transcript = Array.isArray(conv.transcript) ? conv.transcript : []
            convIdRef.current = conv.id
            setSessionId(conv.agentSessionId)
            setMessages(transcript.map((m) => ({ ...m, streaming: false })))
            firstMsgRef.current = transcript.length === 0
            restoredRef.current = true
            setBooting(false)
            return
          }
        } catch { /* listing/loading failed — fall through to a fresh conversation */ }
      }

      setMessages([])
      const sid = await agentChatAPI.newSession(profile)
      setSessionId(sid)
      try {
        const conv = await agentConversationAPI.create({ connectionId, agentSessionId: sid, title: null })
        convIdRef.current = conv.id
      } catch { /* index write failed — chat still works this session, just won't persist */ }
      firstMsgRef.current = true
      restoredRef.current = true
    } catch (e) {
      setBootError(e?.message || 'Failed to start the agent')
    } finally {
      setBooting(false)
    }
  }, [connectionId])

  useEffect(() => { boot() }, [boot])
  useEffect(() => { if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight }, [messages])
  useEffect(() => () => { esRef.current?.close() }, [])

  // Mirror the rendered transcript to the per-user backend so any device resumes
  // it. Skip while a turn streams (avoid a write per token) — onEnd flips
  // `sending` back, re-running this with the settled content.
  useEffect(() => {
    if (!restoredRef.current || booting || sending || !convIdRef.current || !messages.length) return
    agentConversationAPI
      .update(convIdRef.current, { transcript: messages, title: deriveTitle(messages) })
      .catch(() => { /* transient persist failure — retried on the next settled turn */ })
  }, [messages, sending, booting])

  const send = async (preset) => {
    const text = (preset ?? input).trim()
    if (!text || sending || !sessionId || authBlocked) return
    setInput('')
    setMessages((m) => [...m, { role: 'user', content: text }, { role: 'assistant', content: '', tools: [], streaming: true }])
    setSending(true)
    try {
      const toSend = firstMsgRef.current ? withConnectionContext(text, connectionId, connectionName) : text
      firstMsgRef.current = false
      let streamId
      try {
        streamId = await agentChatAPI.startChat(sessionId, toSend)
      } catch {
        // The reused session may no longer exist (e.g. the agent was redeployed).
        // Spin up a fresh one and retry once so the message isn't lost; the UI
        // transcript is preserved even though the agent's server-side context resets.
        const profile = profileRef.current || (await agentChatAPI.bootstrap(connectionId)).profile
        const sid = await agentChatAPI.newSession(profile)
        setSessionId(sid)
        // Re-point the conversation index at the new agent session.
        if (convIdRef.current) {
          agentConversationAPI.update(convIdRef.current, { agentSessionId: sid }).catch(() => {})
        }
        streamId = await agentChatAPI.startChat(sid, toSend)
      }
      streamIdRef.current = streamId
      esRef.current = agentChatAPI.streamChat(streamId, {
        onToken: (t) => setMessages((m) => updateLast(m, (a) => ({ ...a, content: a.content + t }))),
        // Each tool call marks the end of an interim reasoning segment ("pulling
        // the schema…"). Drop that prose from the bubble — keep only the concise
        // tool step in the collapsible activity — so the bubble ends with just the
        // final answer (the text after the last tool).
        onTool: (d) => setMessages((m) => updateLast(m, (a) => ({ ...a, content: '', tools: [...(a.tools || []), toolLabel(d)] }))),
        onEnd: () => { setMessages((m) => updateLast(m, (a) => ({ ...a, streaming: false }))); setSending(false); esRef.current = null },
        onError: () => { setMessages((m) => updateLast(m, (a) => ({ ...a, streaming: false, error: true }))); setSending(false); esRef.current = null },
      })
    } catch (e) {
      setMessages((m) => updateLast(m, (a) => ({ ...a, streaming: false, error: true, content: a.content || `Error: ${e?.message || 'request failed'}` })))
      setSending(false)
    }
  }

  const stop = async () => {
    if (streamIdRef.current) await agentChatAPI.cancel(streamIdRef.current)
    esRef.current?.close(); esRef.current = null
    setMessages((m) => updateLast(m, (a) => ({ ...a, streaming: false })))
    setSending(false)
  }

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
  }

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <div className={styles.title}>DeepSQL Agent</div>
        <div className={styles.headerRight}>
          {connectionName && (
            <span className={styles.connBadge}><Database size={12} /> {connectionName}</span>
          )}
          <button className={styles.newChat} onClick={() => boot({ fresh: true })} disabled={booting} title="New chat">
            <Plus size={14} /> New chat
          </button>
        </div>
      </header>

      <div className={styles.messages} ref={listRef}>
        {booting && <div className={styles.notice}><Loader2 size={14} className={styles.spin} /> Starting your agent…</div>}
        {bootError && <div className={styles.error}>{bootError} <button onClick={() => boot()} className={styles.retry}>Retry</button></div>}

        {!booting && !bootError && messages.length === 0 && (
          <div className={styles.empty}>
            <div className={styles.emptyTitle}>Ask about your database</div>
            <div className={styles.suggestions}>
              {SUGGESTIONS.map((s) => (
                <button key={s} className={styles.suggestion} onClick={() => send(s)}>{s}</button>
              ))}
            </div>
          </div>
        )}

        {messages.map((m, i) => (
          <div key={i} className={m.role === 'user' ? styles.msgUser : styles.msgAssistant}>
            {m.role === 'assistant' ? (
              <div className={styles.assistantBody}>
                {m.tools?.length > 0 && (
                  <details className={styles.activity} open={m.streaming}>
                    <summary>{m.streaming ? 'Working…' : `${m.tools.length} step${m.tools.length > 1 ? 's' : ''}`}</summary>
                    <ul>{m.tools.map((t, j) => <li key={j}>{t}</li>)}</ul>
                  </details>
                )}
                {m.content
                  ? <AgentMarkdown content={sanitizeAssistantAnswer(m.content)} />
                  : m.streaming && <span className={styles.typing}><span /><span /><span /></span>}
                {m.error && <div className={styles.msgError}>The agent run ended early.</div>}
              </div>
            ) : (
              <div className={styles.userBubble}>{m.content}</div>
            )}
          </div>
        ))}
      </div>

      <div className={styles.composer}>
        <textarea
          className={styles.textarea}
          placeholder={authBlocked ? 'Agent unavailable — reconnect above' : sessionId ? 'Message the DeepSQL Agent…' : 'Starting…'}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKeyDown}
          rows={1}
          disabled={!sessionId || booting || authBlocked}
        />
        {sending ? (
          <button className={styles.stopBtn} onClick={stop} title="Stop"><Square size={15} /></button>
        ) : (
          <button className={styles.sendBtn} onClick={() => send()} disabled={!input.trim() || !sessionId || authBlocked} title="Send"><ArrowUp size={16} /></button>
        )}
      </div>
    </div>
  )
}
