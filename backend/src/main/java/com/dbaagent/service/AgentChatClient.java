package com.dbaagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Synchronous client for the DeepSQL Agent webui API, reached over the compose
 * network (internal — not via the gated /agent-api proxy). Used by non-browser
 * channels (Slack, etc.) that need a single final answer rather than a live SSE
 * stream: it starts a turn, consumes the stream server-side, and returns the
 * assembled text plus a compact tool-step summary.
 */
@Service
public class AgentChatClient {
    private static final Logger log = LoggerFactory.getLogger(AgentChatClient.class);

    // Cookie jar: the agent scopes sessions by hermes_profile. Without
    // /api/profile/switch first, session/new(profile=u-…) then chat/start
    // returns 404 "Session not found" under the default profile.
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .cookieHandler(cookies)
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Base URL of the agent webui. Self-host Compose sets
     * {@code AGENT_WEBUI_URL=http://host.docker.internal:8787} (no deepsql-agent
     * service in the four-container stack). Override for a dedicated agent
     * container on the compose network.
     */
    @Value("${agent.webui-url:http://host.docker.internal:8787}")
    private String webuiUrl;

    /** Hard ceiling on a single agent turn for a channel reply. */
    @Value("${agent.channel-turn-timeout-seconds:300}")
    private long turnTimeoutSeconds;

    public record AgentReply(boolean ok, String text, List<String> toolSteps, String error) {
        public static AgentReply ok(String text, List<String> steps) { return new AgentReply(true, text, steps, null); }
        public static AgentReply fail(String error) { return new AgentReply(false, null, List.of(), error); }
    }

    /**
     * Return a usable session id for the given profile: reuse {@code existingSessionId}
     * when present, otherwise create a fresh session and auto-approve its read-only
     * tool surface (channels are non-interactive). Returns null on failure.
     */
    /**
     * Why a session could not be created, for callers that surface a reason to the user.
     * {@code sessionId} non-null means success and {@code failureReason} is null.
     */
    public record SessionAttempt(String sessionId, String failureReason) {
        boolean ok() { return sessionId != null; }
    }

    public String ensureSession(String profile, String existingSessionId) {
        return ensureSessionDetailed(profile, existingSessionId).sessionId();
    }

    public SessionAttempt ensureSessionDetailed(String profile, String existingSessionId) {
        try {
            switchProfile(profile);
        } catch (Exception e) {
            log.warn("Could not switch agent profile to {} (webui={}): {}",
                profile, webuiUrl, e.toString());
            return new SessionAttempt(null, describe(e));
        }
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            return new SessionAttempt(existingSessionId, null);
        }
        try {
            JsonNode created = postJson("/api/session/new", Map.of(
                "profile", profile,
                "enabled_toolsets", List.of("deepsql", "skills")));
            String sessionId = text(created.path("session").path("session_id"));
            if (sessionId == null) sessionId = text(created.path("session_id"));
            if (sessionId == null) {
                log.warn("Agent session/new returned no session_id for profile {}", profile);
                return new SessionAttempt(null,
                    "the agent runtime accepted the request but returned no session id");
            }
            // Best-effort: auto-approve read-only tools so the turn doesn't block on approval.
            try {
                postJson("/api/session/yolo", Map.of("session_id", sessionId, "enabled", true));
            } catch (Exception e) {
                log.debug("session/yolo failed for {}: {}", sessionId, e.getMessage());
            }
            return new SessionAttempt(sessionId, null);
        } catch (Exception e) {
            // toString(), not getMessage(): a NullPointerException or a connect failure
            // carries a null message, and "Could not create agent session: null" says
            // nothing at all. The class name alone distinguishes "refused to connect"
            // from "rejected the request". The exception is attached so the stack is
            // available at DEBUG without making the common case unreadable.
            log.warn("Could not create agent session for profile {} at {}: {}",
                profile, webuiUrl, e.toString());
            log.debug("agent session/new failure detail", e);
            return new SessionAttempt(null, describe(e));
        }
    }

    /** Activate the hermes_profile cookie for subsequent webui API calls. */
    private void switchProfile(String profile) throws Exception {
        if (profile == null || profile.isBlank()) return;
        postJson("/api/profile/switch", Map.of("name", profile));
    }

    /**
     * Start a turn and block until it finishes (or times out), returning the
     * assembled assistant text and the tool steps it ran.
     */
    public AgentReply sendAndAwait(String sessionId, String message) {
        String streamId;
        try {
            JsonNode started = postJson("/api/chat/start", Map.of(
                "session_id", sessionId,
                "message", message));
            streamId = text(started.path("stream_id"));
            if (streamId == null) {
                return AgentReply.fail("agent did not start a turn");
            }
        } catch (Exception e) {
            return AgentReply.fail("could not start agent turn: " + e.getMessage());
        }
        return consumeStream(streamId);
    }

    private AgentReply consumeStream(String streamId) {
        String url = webuiUrl + "/api/chat/stream?stream_id=" + URLEncoder.encode(streamId, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(turnTimeoutSeconds + 10))
            .GET()
            .build();
        StringBuilder answer = new StringBuilder();
        List<String> toolSteps = new ArrayList<>();
        boolean ended = false;
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                return AgentReply.fail("agent stream HTTP " + resp.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                String event = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (event == null) continue;
                        switch (event) {
                            case "token" -> answer.append(textField(data, "text"));
                            case "tool" -> {
                                String s = toolStep(data);
                                if (s != null) toolSteps.add(s);
                                // Everything streamed before a tool call is interim
                                // reasoning ("I'm pulling the schema…"). Channel users
                                // only want the final answer, so drop it — the text
                                // after the LAST tool is the real reply.
                                answer.setLength(0);
                            }
                            case "stream_end", "done" -> ended = true;
                            default -> { /* metering, context_status, interim_assistant, title — ignore */ }
                        }
                        if (ended) break;
                    }
                }
            }
        } catch (Exception e) {
            return AgentReply.fail("agent stream error: " + e.getMessage());
        }
        String text = answer.toString().trim();
        if (text.isEmpty() && !ended) {
            return AgentReply.fail("agent run ended before producing an answer");
        }
        return AgentReply.ok(text, toolSteps);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode postJson(String path, Map<String, Object> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(webuiUrl + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            // The body carries the reason the status code alone does not. An agent
            // runtime hardened to require a login answers
            // {"detail":"Unauthorized","reason":"no_cookie"} — without that, a 401 is
            // indistinguishable from the agent being down, which sends whoever is
            // debugging after the wrong fix. Truncated so an HTML error page cannot
            // land whole in the log.
            String detail = resp.body() == null ? "" : resp.body().strip();
            if (detail.length() > 300) detail = detail.substring(0, 300) + "…";
            throw new RuntimeException("POST " + path + " -> HTTP " + resp.statusCode()
                + (detail.isEmpty() ? "" : " " + detail));
        }
        return objectMapper.readTree(resp.body());
    }

    /**
     * A one-line reason fit to show an operator. The two failures look identical from
     * the outside and have opposite fixes: nothing is listening (start the runtime, or
     * agent.webui-url points at the wrong place) versus something answered and said no
     * (authenticate to it, or its API changed).
     */
    private String describe(Exception e) {
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException) {
            return "cannot reach the agent runtime at " + webuiUrl
                + " — is it running, and is agent.webui-url correct?";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "the agent runtime at " + webuiUrl + " did not respond in time";
        }
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return "the agent runtime at " + webuiUrl + " failed with " + e.getClass().getSimpleName();
        }
        return "the agent runtime at " + webuiUrl + " rejected the request: " + msg;
    }

    private String text(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asText(null);
    }

    private String textField(String data, String field) {
        try {
            return objectMapper.readTree(data).path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** Build a short label for a tool.started event ("SQL · …" / "skill · …" / name). */
    private String toolStep(String data) {
        try {
            JsonNode d = objectMapper.readTree(data);
            String name = d.path("name").asText("").replaceFirst("^mcp_deepsql_", "").replaceFirst("^skill_view$", "skill");
            JsonNode args = d.path("args");
            String query = args.path("query").asText("");
            if (!query.isBlank()) return "SQL · " + query.replaceAll("\\s+", " ").trim();
            String skill = args.path("name").asText("");
            if (!skill.isBlank()) return "skill · " + skill;
            return name.isBlank() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }
}
