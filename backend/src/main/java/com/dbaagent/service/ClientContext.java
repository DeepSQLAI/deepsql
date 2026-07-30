package com.dbaagent.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Origin metadata describing *which client surface* a request came from.
 *
 * The standard JWT/session machinery already tells us *who* the actor is.
 * This record fills in the orthogonal dimension: was the request initiated
 * through the web Editor, the CLI in a terminal, an MCP server invoked by
 * Claude Code, etc. It's used purely for audit metadata — the policy
 * decision is driven by role + connection ACL, not by client type.
 *
 * Clients send three headers; missing values fall back to "unknown" rather
 * than failing, so legacy callers (the SQL Editor before this change, any
 * curl request) still work:
 *
 *   X-DeepSQL-Client-Type      cli | mcp | editor | unknown
 *   X-DeepSQL-Client-Agent     claude-code | cursor | codex | terminal | web | <free-form>
 *   X-DeepSQL-Client-Version   semver-ish; whatever the client wants to report
 *
 * For the MCP path, `clientAgent` is the editor that invoked the MCP server
 * (Claude Desktop, Cursor, etc.) — the MCP shim forwards the value of the
 * existing `DEEPSQL_MCP_USER_ID` env var. For the CLI, it's the human's
 * shell by default; agents that shell out can override with --caller-agent.
 */
public record ClientContext(
    String clientType,
    String clientAgent,
    String clientVersion
) {

    public static final String HEADER_TYPE = "X-DeepSQL-Client-Type";
    public static final String HEADER_AGENT = "X-DeepSQL-Client-Agent";
    public static final String HEADER_VERSION = "X-DeepSQL-Client-Version";

    private static final String UNKNOWN = "unknown";

    public ClientContext {
        clientType = normalize(clientType, UNKNOWN);
        clientAgent = normalize(clientAgent, null);
        clientVersion = normalize(clientVersion, null);
    }

    /**
     * Pull a ClientContext from the request headers. Returns a non-null
     * record even when headers are absent, so callers don't have to
     * null-check. `clientType` defaults to "unknown" in that case.
     */
    public static ClientContext fromRequest(HttpServletRequest request) {
        if (request == null) {
            return unknown();
        }
        return new ClientContext(
            request.getHeader(HEADER_TYPE),
            request.getHeader(HEADER_AGENT),
            request.getHeader(HEADER_VERSION)
        );
    }

    /** A sentinel for server-internal calls (background jobs, etc.). */
    public static ClientContext internal() {
        return new ClientContext("internal", null, null);
    }

    /** A sentinel for cases where no request context is available. */
    public static ClientContext unknown() {
        return new ClientContext(UNKNOWN, null, null);
    }

    /**
     * Web Editor calls typically don't send these headers (yet). We default
     * to this when the request is clearly browser-driven and we don't want
     * the audit row to read "unknown" for our own UI.
     */
    public static ClientContext editorWeb() {
        return new ClientContext("editor", "web", null);
    }

    private static String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        // Cap each field at 120 chars so a malicious client can't bloat
        // every audit row in the security_events table.
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }
}
