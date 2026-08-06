// Client for the native "Agent" chat tab (AgentChatPanel — DeepSQL's own React UI).
//
// Two hops:
//  1. POST /api/agent/session  → Spring backend (cookie auth) resolves/provisions
//     the user's agent profile and returns { profile }.
//  2. /agent-api/*             → the DeepSQL Agent HTTP API (Vite-proxied to :8787;
//     customized Hermes runtime): profile/switch, session/new, chat/start, then
//     chat/stream over SSE.
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

/**
 * Bind the agent API to this user's profile via the upstream `hermes_profile` cookie.
 *
 * The agent scopes session visibility to the active profile. Spring returns
 * `u-<username>` from /api/agent/session; if we create a session under that
 * profile but never switch, subsequent /api/session/yolo and /api/chat/start
 * calls 404 with "Session not found" (the Agent tab surfaces this as a boot
 * failure / early 500). credentials:"include" sends the Set-Cookie back.
 */
async function switchAgentProfile(profile) {
  if (!profile) return;
  await postJson(`${AGENT_BASE}/api/profile/switch`, { name: profile });
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
    const data = await postJson("/api/agent/session", { connectionId });
    // Must happen before any session/new / resume path that hits /agent-api.
    try {
      await switchAgentProfile(data?.profile);
    } catch {
      /* older agent / missing profile — newSession may still work on default */
    }
    return data;
  },

  /** Create a lean DBA chat session for this profile; returns the session id. */
  async newSession(profile) {
    // Idempotent re-bind in case bootstrap's switch was skipped or the cookie aged out.
    try {
      await switchAgentProfile(profile);
    } catch {
      /* non-fatal */
    }
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
