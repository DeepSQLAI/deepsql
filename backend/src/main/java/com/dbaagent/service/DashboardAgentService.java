package com.dbaagent.service;

import com.dbaagent.service.security.AccessControlService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
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
 * Dashboard generator — the embedded DeepSQL Agent as a coding agent
 * (customized Hermes runtime; see agent/README.md).
 *
 * <p>The agent doesn't fill in a rigid spec. It designs a dashboard freely —
 * any layout, filters, date pickers, chart types, styling it wants — after
 * grounding on the brain/schema and verifying every widget's query with
 * {@code execute_sql}. The artifact fetches data at runtime through the
 * injected {@code deepsql.query(sql)} bridge (see DashboardQueryController), so
 * it never holds DB creds and every query stays read-only + access-scoped.
 *
 * <p>The agent emits progressively: one {@code dashboard-shell} fenced block
 * (page chrome + named, empty widget slots), then one {@code dashboard-widget}
 * block per KPI/chart, each only after ITS OWN query is verified. A
 * {@link ChunkListener} fires per block as it closes — well before the whole
 * turn ends — so DashboardGenerationController can stream each piece to the
 * UI as it's ready instead of the canvas staying blank for the whole build.
 * {@link #assembleFromChunks} substitutes each widget into its shell slot to
 * produce the final document once the turn completes; {@link #extractHtml}
 * falls back to the older single-block contract if no shell/widget fences are
 * present at all, so a reply that predates this contract still works.
 *
 * <p>The old JSON-spec contract (metrics/charts/tables + a {{placeholder}}
 * substitution engine + a fixed renderer) is gone: it couldn't express real SQL
 * (e.g. a Unix-epoch date filter) and boxed the agent in.
 */
@Service
public class DashboardAgentService {
    private static final Logger log = LoggerFactory.getLogger(DashboardAgentService.class);

    /** Progress sink for the streaming endpoint; NOOP for the blocking one. */
    public interface StepListener {
        StepListener NOOP = (type, message) -> { };
        void step(String type, String message);
    }

    /**
     * Progressive-render sink: fired the instant the agent finishes a
     * dashboard-shell or dashboard-widget block (before the whole turn ends),
     * so the canvas can fill in piece by piece. NOOP for callers that only want
     * the final assembled artifact (the blocking /generate endpoint).
     */
    public interface ChunkListener {
        ChunkListener NOOP = (kind, id, html) -> { };
        void chunk(String kind, String id, String html);
    }

    /** Artifact spec version stored in saved_dashboards.dashboardConfig. */
    private static final int ARTIFACT_VERSION = 3;
    private static final int MAX_HTML_CHARS = 400_000;

    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final AgentBridgeService agentBridgeService;
    private final AgentChatClient agentChatClient;
    private final ChatClient intentChatClient;

    public DashboardAgentService(ObjectMapper objectMapper,
                                 AccessControlService accessControlService,
                                 AgentBridgeService agentBridgeService,
                                 AgentChatClient agentChatClient,
                                 ChatModel chatModel) {
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.agentBridgeService = agentBridgeService;
        this.agentChatClient = agentChatClient;
        this.intentChatClient = ChatClient.builder(chatModel).build();
    }

    public Map<String, Object> generate(String connectionId, String prompt, Object currentConfig, StepListener listener) {
        return generate(connectionId, prompt, currentConfig, listener, null);
    }

    public Map<String, Object> generate(String connectionId, String prompt, Object currentConfig,
                                         StepListener listener, ChunkListener chunkListener) {
        List<Map<String, Object>> trace = new ArrayList<>();
        StepListener l = listener == null ? StepListener.NOOP : listener;
        ChunkListener c = chunkListener == null ? ChunkListener.NOOP : chunkListener;

        String username = accessControlService.requireCurrentUsername();
        String profile = agentBridgeService.ensureProfileForUser(username, connectionId);
        // Fresh session per generation — an isolated coding task, not the user's chat thread.
        String sessionId = agentChatClient.ensureSession(profile, null);
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The DeepSQL agent is unavailable right now.");
        }

        // Most messages are a real build/edit ask, so default to the full pipeline —
        // only skip it for something that plainly isn't one (a greeting, a question
        // about the tool itself). Answering "hi" by grounding on the schema, writing
        // SQL, and self-reviewing an HTML document is where the multi-minute replies
        // to trivial messages came from. Classify BEFORE emitting any step: a chat-only
        // turn should show nothing but the generic "Working" spinner, not a "Handing off
        // to the DeepSQL agent" trace that implies a build is underway.
        if (isChatOnly(prompt)) {
            AgentChatClient.AgentReply chatReply = agentChatClient.sendAndAwait(sessionId, buildChatTask(prompt));
            if (chatReply.ok() && chatReply.text() != null && !chatReply.text().isBlank()) {
                Map<String, Object> chat = new LinkedHashMap<>();
                chat.put("chat", true);
                chat.put("reply", chatReply.text().trim());
                chat.put("trace", trace);
                log.info("Dashboard chat-only reply ({} chars) for prompt: {}",
                    chat.get("reply").toString().length(),
                    prompt == null ? "" : prompt.trim());
                return chat;
            }
            // Ambiguous or the agent didn't just answer — fall through to a real build
            // rather than surfacing a failure for what might be a legitimate request.
        }

        emit(l, trace, "grounding", "Handing off to the DeepSQL agent…");
        emit(l, trace, "planning", "Agent is grounding, writing SQL, and coding the dashboard…");
        // Without this, the UI showed nothing but the "planning" line above for the
        // whole 3-4 minute build. Each tool call (schema lookup, a verified SQL
        // query, a skill invocation) now becomes its own step, so the trace keeps
        // moving instead of looking stuck.
        AgentChatClient.AgentReply reply = agentChatClient.sendAndAwait(
            sessionId, buildTask(connectionId, prompt, currentConfig),
            toolLabel -> emit(l, trace, "sql", truncateStepLabel(toolLabel)),
            chunk -> c.chunk(chunk.kind(), chunk.id(), chunk.html()));
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

    // ── chat-only detection ─────────────────────────────────────────────────

    // A one-word classification call, not a keyword match: a fixed word list can't
    // tell "make it prettier" or "no, the other one" from a real edit ask. This is a
    // direct ChatModel call (no agent session, no tools) so it stays fast — the whole
    // point is answering "hi" without paying for a grounding+SQL+self-review turn.
    // Biased toward CHAT=false (i.e. toward the full pipeline) in the prompt itself:
    // a wrong "this is chat" guess on a real request is far worse than an occasional
    // unnecessary grounding pass on a genuine one-word greeting.
    private static final String INTENT_SYSTEM_PROMPT = """
        Classify one chat message from a BI dashboard builder. Decide whether it is a
        request to build, edit, or change a chart/dashboard/metric/data view (CHAT=false),
        or plainly just conversation — a greeting, thanks, or a question about the tool
        itself with no dashboard content in it (CHAT=true).

        If in doubt, answer false — treat anything that could plausibly be about the data
        or the dashboard's content/appearance as a real request, even if short or vague
        ("make it prettier", "no, the other one", "add a filter").

        Reply with exactly one word, "true" or "false". No punctuation, no explanation.
        """;
    private static final int CHAT_ONLY_MAX_CHARS = 200;

    private boolean isChatOnly(String prompt) {
        if (prompt == null) return false;
        String p = prompt.trim();
        if (p.isEmpty() || p.length() > CHAT_ONLY_MAX_CHARS) return false;
        try {
            List<Message> messages = List.of(new SystemMessage(INTENT_SYSTEM_PROMPT), new UserMessage(p));
            String verdict = intentChatClient.prompt().messages(messages).call().content();
            return verdict != null && verdict.trim().toLowerCase().startsWith("true");
        } catch (Exception e) {
            log.warn("Chat-intent classification failed, defaulting to full pipeline: {}", e.getMessage());
            return false;
        }
    }

    private String buildChatTask(String prompt) {
        return "The user sent this message in the dashboard builder's chat: \"" + prompt.trim() + "\"\n\n"
            + "It does not read as a request to build or change a chart/dashboard — it looks like a "
            + "greeting, small talk, or a question about what you can do. Reply briefly and naturally "
            + "in plain text (no HTML, no code block, no tool calls, no grounding, no SQL). If it's a "
            + "greeting, greet back and invite them to describe a dashboard. Keep it to 1-2 sentences.";
    }

    // ── the task the agent runs ────────────────────────────────────────────

    private String buildTask(String connectionId, String prompt, Object currentConfig) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build a beautiful, self-contained, read-only BI dashboard for DeepSQL connection ")
          .append(connectionId)
          .append(", emitted progressively as a shell block plus one widget block per KPI/chart. ")
          .append("Load your `dashboard-design` skill and follow it.\n\n");
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
               A dashboard driven by a time period (daily/recent activity, trends, "momentum") gets a real
               date-range control in the shell's header — quick-range buttons (e.g. Last 7/30/90 days) PLUS
               explicit start/end date inputs and an Apply action, not just one of those. See the skeleton's
               header bar for the exact shape to build on. Write ONE real, correct, read-only SELECT per widget
               (table-qualified). There is NO placeholder convention — you write normal SQL. For a Unix-epoch
               date column, filter on the epoch directly (e.g. col BETWEEN UNIX_TIMESTAMP('2026-07-01') AND
               UNIX_TIMESTAMP('2026-07-08')); build such SQL in JS from the picker's values and pass the
               finished string to deepsql.query().
            3. VERIFY each widget's query with execute_sql and READ the rows — date windows bounded and inside
               range (never future), KPI value types right (a name is text, money is currency), totals plausible
               vs a COUNT — BEFORE emitting that widget's block (step 5 below). Fix and re-run until correct.
            4. INTENT CHECKLIST — before emitting the shell, list every explicit ask (each chart, each metric, each
               UI control like a date range picker with the requested default, e.g. today) and confirm your planned
               widgets cover ALL of them.
            5. Emit progressively, as TWO KINDS of fenced blocks, each ONLY after its own SQL is verified (step 3):

               a. FIRST, exactly one shell block — the page chrome and an empty, named slot per widget:
                  ```dashboard-shell
                  <!doctype html>...<head><style>...</style></head><body>
                    <div class="wrap">
                      <header>...</header>
                      <section class="kpis">
                        <div data-widget="revenue-total"></div>
                        <div data-widget="orders-count"></div>
                      </section>
                      <section class="charts">
                        <div data-widget="revenue-trend"></div>
                      </section>
                    </div>
                  </body></html>
                  ```
                  Pick a short, stable, kebab-case id per widget (e.g. "revenue-total") — you will reuse this
                  EXACT id in that widget's own block below. The shell itself has no <script> that queries
                  data; each widget brings its own.

               b. THEN, one block per widget, in any order, each independently self-contained (its own markup
                  AND the <script> that queries and renders into ITS OWN slot only):
                  ```dashboard-widget id="revenue-total"
                  <article class="card hero">
                    <p class="eyebrow">Revenue</p>
                    <p class="value" id="revenue-total-val">—</p>
                  </article>
                  <script>
                    deepsql.ready(async () => {
                      try {
                        const { rows } = await deepsql.query("SELECT SUM(o.total_amount) FROM public.orders o ...");
                        document.getElementById('revenue-total-val').textContent = deepsql.charts.format(rows?.[0]?.[0] ?? 0);
                      } catch (e) {
                        document.getElementById('revenue-total-val').textContent = '—';
                      }
                    });
                  </script>
                  ```
                  A widget's <script> must reference ONLY elements inside its own block (by an id namespaced
                  with the widget id, as above) — never reach into another widget's slot. Use
                  deepsql.charts.bar/line/donut for every chart — they give hover tooltips, number formatting,
                  and empty-state handling; do not hand-write chart SVG. Guard every value against
                  undefined/null/NaN (fall back to a dash) so no "undefined" ever renders. Wire any control
                  (date range, dropdown) inside the relevant widget's own script, re-querying on change.

            6. SELF-REVIEW before your final message ends: reread everything you emitted as the end user — every
               chart is a deepsql.charts.* call with the right label/value columns (none left blank), no
               undefined/NaN can reach the screen (esp. KPI sub-labels and % captions), every asked-for control
               is wired, every widget id you referenced in the shell has a matching dashboard-widget block, and
               no internals are visible. If self-review finds a problem in a widget you already emitted, emit a
               CORRECTED dashboard-widget block with the SAME id as your last action — the same id re-emitted
               replaces what was shown before. A blank chart or an "undefined" label is a failed build — fix it
               before your message ends, don't leave it for the user to find.

            TWO HARD RULES (from the skill):
            - NEVER show internals to the user. No table/column names, no SQL, no connection id/UUID, no schema or
              database jargon anywhere visible (titles, labels, descriptions, captions, errors). Use plain business
              language only. Keep all schema/SQL reasoning to yourself. (This is a security requirement.)
            - Use the INJECTED theme: Maven Pro font + a black/white/grey palette are already set via CSS variables
              (--ds-bg, --ds-surface, --ds-ink, --ds-ink-2, --ds-line, --ds-grad, --ds-soft-1/2/3, --ds-radius,
              --ds-shadow). Do NOT import fonts or set font-family. Stay monochrome; use a subtle soft color/gradient
              ONLY to highlight the 1–2 most important KPIs. Clean, minimal, lots of whitespace — not dark or neon.

            Output your FINAL message as ONLY the fenced blocks described in step 5 above (one ```dashboard-shell```
            first, then one ```dashboard-widget id="..."``` per widget) — no prose, no tool calls after the last one.
            Do NOT wrap the whole thing in a single ```html block — the shell and each widget are SEPARATE fences.""");
        return sb.toString();
    }

    // ── artifact extraction ────────────────────────────────────────────────

    // Matches the same dashboard-shell/dashboard-widget fences AgentChatClient's
    // live chunk scanner does — kept as a SEPARATE pattern (not shared) because
    // this one runs once, after the fact, over the complete final message, with
    // no incremental-scan-position bookkeeping needed.
    private static final Pattern SHELL_OR_WIDGET_FENCE = Pattern.compile(
        "```dashboard-(shell|widget)(?:\\s+id=\"([^\"]+)\")?\\s*\\n(.*?)\\n```",
        Pattern.DOTALL);

    /**
     * Assembles the final document from the agent's shell + widget chunks (the
     * progressive-render contract), substituting each widget's HTML+script into
     * its {@code [data-widget=id]} slot in the shell. Falls back to the older
     * single ```html block contract if no shell/widget fences are present at
     * all, so an agent turn that (for whatever reason) still emits one block
     * — or any reply predating this contract — keeps working unchanged.
     */
    private String extractHtml(String text) {
        if (text == null || text.isBlank()) return null;
        String assembled = assembleFromChunks(text);
        if (assembled != null) return assembled;
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

    private String assembleFromChunks(String text) {
        Matcher m = SHELL_OR_WIDGET_FENCE.matcher(text);
        String shell = null;
        // LinkedHashMap: a widget id emitted twice (a self-review correction re-
        // emitting the same id) keeps only the LAST occurrence, but a first-seen
        // id's position in the shell's substitution order is irrelevant — each
        // is substituted into its own named slot, not appended in sequence.
        Map<String, String> widgets = new LinkedHashMap<>();
        while (m.find()) {
            String kind = m.group(1);
            String id = m.group(2);
            String body = m.group(3);
            if ("shell".equals(kind)) {
                shell = body;
            } else if (id != null) {
                widgets.put(id, body);
            }
        }
        if (shell == null) return null;
        String html = shell;
        for (Map.Entry<String, String> e : widgets.entrySet()) {
            html = substituteWidgetSlot(html, e.getKey(), e.getValue());
        }
        return html;
    }

    // Replaces <div data-widget="id"></div> (or any content already inside it,
    // e.g. a loading placeholder the shell shipped with) with the widget's
    // verified markup+script. Matches the OPENING tag separately from its
    // contents so a self-closing or non-empty placeholder slot both work.
    private String substituteWidgetSlot(String shellHtml, String widgetId, String widgetHtml) {
        Pattern slot = Pattern.compile(
            "(<[a-zA-Z0-9]+[^>]*data-widget=\"" + Pattern.quote(widgetId) + "\"[^>]*>)(.*?)(</[a-zA-Z0-9]+>)",
            Pattern.DOTALL);
        Matcher sm = slot.matcher(shellHtml);
        if (sm.find()) {
            return sm.replaceFirst(Matcher.quoteReplacement(sm.group(1)) + Matcher.quoteReplacement(widgetHtml) + Matcher.quoteReplacement(sm.group(3)));
        }
        // Self-closing slot (<div data-widget="id" />) — replace the whole tag.
        Pattern selfClosing = Pattern.compile(
            "<[a-zA-Z0-9]+[^>]*data-widget=\"" + Pattern.quote(widgetId) + "\"[^>]*/>");
        Matcher scm = selfClosing.matcher(shellHtml);
        if (scm.find()) {
            return scm.replaceFirst(Matcher.quoteReplacement("<div data-widget=\"" + widgetId + "\">" + widgetHtml + "</div>"));
        }
        // No matching slot in the shell (shouldn't happen if the agent followed
        // the contract) — leave the shell as-is rather than silently dropping
        // a verified widget's content.
        log.warn("No [data-widget=\"{}\"] slot found in dashboard shell; widget content was not inserted", widgetId);
        return shellHtml;
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

    private static final int MAX_PERSISTED_TRACE_STEPS = 50;
    private static final int MAX_STEP_LABEL_CHARS = 140;

    private static String truncateStepLabel(String label) {
        if (label == null) return null;
        return label.length() > MAX_STEP_LABEL_CHARS ? label.substring(0, MAX_STEP_LABEL_CHARS) + "…" : label;
    }

    private void emit(StepListener l, List<Map<String, Object>> trace, String type, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("message", message);
        trace.add(e);
        // Only the live SSE step feed matters once the build finishes — nothing
        // reads the persisted trace afterward — so cap it rather than let a long
        // build's dozens of SQL steps bloat dashboardConfig.
        if (trace.size() > MAX_PERSISTED_TRACE_STEPS) trace.remove(0);
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
