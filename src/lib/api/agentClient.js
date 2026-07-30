// Client for the native "Agent" chat tab.
//
// Two hops:
//  1. POST /api/agent/session  → Spring backend (cookie auth) resolves/provisions
//     the user's agent profile and returns { profile }.
//  2. /agent-api/*             → DeepSQL Agent chat service (Vite-proxied to :8787):
//     create a session, start a turn, stream it over SSE.
//
// SSE event shapes:
//   token          { text }
//   tool           { event_type:"tool.started",  name, args, tid }
//   tool_complete  { event_type:"tool.completed", name, preview, tid }
//   stream_end | done   → turn finished
import { requestSessionRefresh } from "./client";

const AGENT_BASE = "/agent-api";

async function postJson(url, body, _retried = false) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(body || {}),
  });
  // /agent-api is gated by the DeepSQL session cookie (nginx auth_request). The
  // 15-min access token can lapse while the Agent tab sits idle, so on a 401
  // refresh the session once (deduped with the app-wide refresh) and retry. This
  // keeps an actively-logged-in UI's agent working without a visible error.
  if (res.status === 401 && !_retried) {
    try {
      await requestSessionRefresh();
    } catch {
      /* refresh failed — fall through and surface the original 401 */
    }
    return postJson(url, body, true);
  }
  if (!res.ok) throw new Error(`${url} → ${res.status}`);
  return res.json();
}

/** Prepend a one-line connection context so the agent grounds on the active DB
 *  without the user pasting a UUID (the provisioned USER.md isn't injected into
 *  webui sessions). Sent to the agent only — the UI displays the raw message. */
export function withConnectionContext(message, connectionId, connectionName) {
  if (!connectionId) return message;
  const label = connectionName ? `${connectionName} (id ${connectionId})` : connectionId;
  return `[Active DeepSQL connection: ${label}. Use this connection unless I name another.]\n\n${message}`;
}

export const agentChatAPI = {
  /** Resolve/provision the current user's agent profile (via Spring → cookie auth). */
  async bootstrap(connectionId) {
    return postJson("/api/agent/session", { connectionId });
  },

  /** Create a lean DBA chat session for this profile; returns the session id. */
  async newSession(profile) {
    const data = await postJson(`${AGENT_BASE}/api/session/new`, {
      profile,
      enabled_toolsets: ["deepsql", "skills"],
    });
    const sessionId = data?.session?.session_id || data?.session_id;
    // Best-effort: auto-approve the read-only tool surface for this session.
    try {
      await postJson(`${AGENT_BASE}/api/session/yolo`, { session_id: sessionId, enabled: true });
    } catch { /* non-fatal */ }
    return sessionId;
  },

  /** Start a turn; returns the stream_id to subscribe to. */
  async startChat(sessionId, message) {
    const data = await postJson(`${AGENT_BASE}/api/chat/start`, {
      session_id: sessionId,
      message,
    });
    return data.stream_id;
  },

  /** Subscribe to a turn's SSE stream. Returns the EventSource (caller may .close()). */
  streamChat(streamId, { onToken, onTool, onToolComplete, onEnd, onError } = {}) {
    const es = new EventSource(
      `${AGENT_BASE}/api/chat/stream?stream_id=${encodeURIComponent(streamId)}`,
      { withCredentials: true },
    );
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      es.close();
      onEnd?.();
    };
    const parse = (e, cb) => { try { cb?.(JSON.parse(e.data)); } catch { /* ignore */ } };
    es.addEventListener("token", (e) => parse(e, (d) => onToken?.(d.text || "")));
    es.addEventListener("tool", (e) => parse(e, (d) => onTool?.(d)));
    es.addEventListener("tool_complete", (e) => parse(e, (d) => onToolComplete?.(d)));
    es.addEventListener("stream_end", finish);
    es.addEventListener("done", finish);
    es.addEventListener("error", () => {
      // EventSource also fires "error" when the server closes after stream_end —
      // only surface a real error if the turn hadn't finished.
      if (!done) { done = true; es.close(); onError?.(new Error("stream error")); }
    });
    return es;
  },

  async cancel(streamId) {
    try { await postJson(`${AGENT_BASE}/api/chat/cancel`, { stream_id: streamId }); } catch { /* ignore */ }
  },
};
