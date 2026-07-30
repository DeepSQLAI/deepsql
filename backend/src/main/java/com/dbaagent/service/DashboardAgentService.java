package com.dbaagent.service;

import com.dbaagent.service.security.AccessControlService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dashboard generator — the embedded DeepSQL (Hermes) agent as a coding agent.
 *
 * <p>The agent doesn't fill in a rigid spec anymore. It <b>writes the whole
 * dashboard as a single self-contained HTML document</b> — any layout, filters,
 * date pickers, chart types, styling it wants, coded directly — after grounding
 * on the brain/schema and verifying every query with {@code execute_sql}. The
 * artifact fetches data at runtime through the injected {@code deepsql.query(sql)}
 * bridge (see DashboardQueryController), so it never holds DB creds and every
 * query stays read-only + access-scoped.
 *
 * <p>The old JSON-spec contract (metrics/charts/tables + a {{placeholder}}
 * substitution engine + a fixed renderer) is gone: it couldn't express real SQL
 * (e.g. a Unix-epoch date filter) and boxed the agent in. This broker just runs
 * one agent turn with the artifact contract, extracts the HTML, and returns it.
 */
@Service
public class DashboardAgentService {
    private static final Logger log = LoggerFactory.getLogger(DashboardAgentService.class);

    /** Progress sink for the streaming endpoint; NOOP for the blocking one. */
    public interface StepListener {
        StepListener NOOP = (type, message) -> { };
        void step(String type, String message);
    }

    /** Artifact spec version stored in saved_dashboards.dashboardConfig. */
    private static final int ARTIFACT_VERSION = 3;
    private static final int MAX_HTML_CHARS = 400_000;

    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final AgentBridgeService agentBridgeService;
    private final AgentChatClient agentChatClient;

    public DashboardAgentService(ObjectMapper objectMapper,
                                 AccessControlService accessControlService,
                                 AgentBridgeService agentBridgeService,
                                 AgentChatClient agentChatClient) {
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.agentBridgeService = agentBridgeService;
        this.agentChatClient = agentChatClient;
    }

    public Map<String, Object> generate(String connectionId, String prompt, Object currentConfig, StepListener listener) {
        List<Map<String, Object>> trace = new ArrayList<>();
        StepListener l = listener == null ? StepListener.NOOP : listener;

        emit(l, trace, "grounding", "Handing off to the DeepSQL agent…");
        String username = accessControlService.requireCurrentUsername();
        String profile = agentBridgeService.ensureProfileForUser(username, connectionId);
        // Fresh session per generation — an isolated coding task, not the user's chat thread.
        String sessionId = agentChatClient.ensureSession(profile, null);
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The DeepSQL agent is unavailable right now.");
        }

        emit(l, trace, "planning", "Agent is grounding, writing SQL, and coding the dashboard…");
        AgentChatClient.AgentReply reply = agentChatClient.sendAndAwait(
            sessionId, buildTask(connectionId, prompt, currentConfig));
        if (!reply.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "The agent couldn't build the dashboard: " + (reply.error() == null ? "it ended early" : reply.error()));
        }

        String html = extractHtml(reply.text());
        if (html == null || html.isBlank()) {
            log.warn("Dashboard agent returned no HTML artifact. Reply head: {}",
                reply.text() == null ? "null" : reply.text().substring(0, Math.min(300, reply.text().length())));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "The agent didn't return a dashboard. Try rephrasing your request.");
        }
        if (html.length() > MAX_HTML_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The generated dashboard was too large to render.");
        }

        String title = extractTitle(html, prompt);
        emit(l, trace, "done", "Dashboard built (" + html.length() + " chars), queries agent-verified");

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("version", ARTIFACT_VERSION);
        cfg.put("renderMode", "artifact");
        cfg.put("title", title);
        cfg.put("html", html);
        cfg.put("trace", trace);
        return cfg;
    }

    // ── the task the agent runs ────────────────────────────────────────────

    private String buildTask(String connectionId, String prompt, Object currentConfig) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build a beautiful, self-contained, read-only BI dashboard as a SINGLE HTML document for ")
          .append("DeepSQL connection ").append(connectionId)
          .append(". Load your `dashboard-design` skill and follow it.\n\n");
        sb.append("User request:\n").append(prompt == null ? "" : prompt.trim()).append("\n\n");

        String currentHtml = currentHtml(currentConfig);
        if (currentHtml != null) {
            sb.append("This is an EDIT — here is the current dashboard's HTML. Apply the request to it and keep ")
              .append("what works; return the FULL updated document:\n```html\n")
              .append(trim(currentHtml, 40_000)).append("\n```\n\n");
        }

        sb.append("""
            Data access — your HTML runs in a sandboxed iframe and CANNOT reach the database directly. Fetch data
            ONLY through the injected async bridge (already read-only + scoped to this connection):
                const { columns, rows } = await deepsql.query("SELECT ...");   // columns: string[], rows: any[][]
            Also available: deepsql.connectionId, and deepsql.ready(fn) which runs fn once the bridge is live.
            Do NOT hardcode result data, do NOT use fetch()/XHR/WebSockets or any external URL/CDN (all blocked) —
            inline every style and script.

            Do this, in order:
            1. Ground: get_brain_context, get_schema, list_business_rules, get_relationships. Obey business rules
               about which table/column/filter/currency a concept uses — quote them, don't guess a similar table.
            2. Design the dashboard from the request: KPIs, charts, tables, and any filters/date pickers asked for.
               Write ONE real, correct, read-only SELECT per widget (table-qualified). There is NO placeholder
               convention — you write normal SQL. For a Unix-epoch date column, filter on the epoch directly
               (e.g. col BETWEEN UNIX_TIMESTAMP('2026-07-01') AND UNIX_TIMESTAMP('2026-07-08')); build such SQL
               in JS from the picker's values and pass the finished string to deepsql.query().
            3. VERIFY every query with execute_sql and READ the rows: date windows bounded and inside range (never
               future), KPI value types right (a name is text, money is currency), totals plausible vs a COUNT.
               Fix and re-run until correct.
            4. INTENT CHECKLIST — before emitting, confirm EVERY explicit ask is satisfied (each chart, each metric,
               and each UI control like a date range picker with the requested default, e.g. today).
            5. Write the single HTML document: inline <style> + <script>, load data via deepsql.query() on load,
               wire your own controls to re-query on change, and show a small inline error near any widget whose
               query fails (don't blank the page). Use deepsql.charts.bar/line/donut for EVERY chart — they give
               hover tooltips, number formatting, and empty-state handling; do not hand-write chart SVG. Guard every
               value against undefined/null/NaN (fall back to a dash) so no "undefined" ever renders.
            6. SELF-REVIEW before emitting: reread the finished HTML as the end user — every chart is a
               deepsql.charts.* call with the right label/value columns (none left blank), no undefined/NaN can reach
               the screen (esp. KPI sub-labels and % captions), every asked-for control is wired, and no internals
               are visible. A blank chart or an "undefined" label is a failed build — fix it before emitting.

            TWO HARD RULES (from the skill):
            - NEVER show internals to the user. No table/column names, no SQL, no connection id/UUID, no schema or
              database jargon anywhere visible (titles, labels, descriptions, captions, errors). Use plain business
              language only. Keep all schema/SQL reasoning to yourself. (This is a security requirement.)
            - Use the INJECTED theme: Maven Pro font + a black/white/grey palette are already set via CSS variables
              (--ds-bg, --ds-surface, --ds-ink, --ds-ink-2, --ds-line, --ds-grad, --ds-soft-1/2/3, --ds-radius,
              --ds-shadow). Do NOT import fonts or set font-family. Stay monochrome; use a subtle soft color/gradient
              ONLY to highlight the 1–2 most important KPIs. Clean, minimal, lots of whitespace — not dark or neon.

            Output your FINAL message as ONLY the complete HTML document in one ```html code block — no prose, no
            tool calls after it.""");
        return sb.toString();
    }

    // ── artifact extraction ────────────────────────────────────────────────

    /** Pull the HTML document out of the agent's final message (fenced or bare). */
    private String extractHtml(String text) {
        if (text == null || text.isBlank()) return null;
        // Prefer the last ```html (or bare ```) fenced block that looks like markup.
        Matcher fence = Pattern.compile("```(?:html)?\\s*(.*?)```", Pattern.DOTALL).matcher(text);
        String candidate = null;
        while (fence.find()) {
            String body = fence.group(1).trim();
            if (looksLikeHtml(body)) candidate = body;
        }
        if (candidate != null) return candidate;
        // Else the substring from the first markup marker to the end.
        Matcher start = Pattern.compile("(?is)<(?:!doctype html|html|body|div|main|section|style|script)\\b").matcher(text);
        if (start.find()) {
            String body = text.substring(start.start()).trim();
            // Trim a trailing ``` if the model left a dangling fence.
            body = body.replaceAll("```\\s*$", "").trim();
            if (looksLikeHtml(body)) return body;
        }
        return null;
    }

    private boolean looksLikeHtml(String s) {
        if (s == null || s.length() < 20) return false;
        String lower = s.toLowerCase();
        return lower.contains("<html") || lower.contains("<!doctype") || lower.contains("<body")
            || lower.contains("<div") || lower.contains("<main") || lower.contains("<section")
            || (lower.contains("<style") && lower.contains("<script"));
    }

    private String extractTitle(String html, String prompt) {
        Matcher t = Pattern.compile("(?is)<title>\\s*(.*?)\\s*</title>").matcher(html);
        if (t.find() && !t.group(1).isBlank()) return trim(stripTags(t.group(1)), 120);
        Matcher h = Pattern.compile("(?is)<h1[^>]*>\\s*(.*?)\\s*</h1>").matcher(html);
        if (h.find() && !h.group(1).isBlank()) return trim(stripTags(h.group(1)), 120);
        return trim(prompt == null || prompt.isBlank() ? "Dashboard" : prompt.trim(), 80);
    }

    private String currentHtml(Object currentConfig) {
        if (currentConfig == null) return null;
        try {
            JsonNode node = objectMapper.valueToTree(currentConfig);
            if (node != null && node.hasNonNull("html")) {
                String html = node.get("html").asText(null);
                return html == null || html.isBlank() ? null : html;
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ── small helpers ──────────────────────────────────────────────────────

    private void emit(StepListener l, List<Map<String, Object>> trace, String type, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("message", message);
        trace.add(e);
        try { l.step(type, message); } catch (Exception ignored) { }
    }

    private String stripTags(String s) {
        return s.replaceAll("(?s)<[^>]*>", "").trim();
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
