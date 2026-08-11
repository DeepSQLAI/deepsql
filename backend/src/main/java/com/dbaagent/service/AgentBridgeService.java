package com.dbaagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Bridges a DeepSQL user to their isolated DeepSQL Agent profile — the per-user
 * SSO bridge.
 *
 * <p>Maps a username to the profile {@code u-<sanitized-username>} and, on each
 * Agent-tab open, asks the agent container to create (first use) or token-refresh
 * that profile, passing the authenticated user's identity + token + default
 * connection. The provisioned profile's MCP server then runs AS that user, so the
 * backend enforces the user's RBAC, connection scope, and audit.
 *
 * <p>The Java backend can't run the agent shell tooling itself (no agent runtime
 * in its image), so provisioning is delegated over the compose network to the
 * agent's internal, secret-gated provisioner endpoint. Failures never block the
 * tab — we still return the profile name.
 */
@Service
public class AgentBridgeService {
    private static final Logger log = LoggerFactory.getLogger(AgentBridgeService.class);

    /**
     * Name stamped on the auto-managed MCP token the bridge mints per user. Used
     * to find + revoke the previous one on each rotation so the token table never
     * piles up one row per Agent-tab open.
     */
    private static final String AGENT_TOKEN_NAME = "DeepSQL Agent (auto)";

    /**
     * Name for the token minted for non-browser channels (Slack, etc.). Distinct
     * from the {@code (auto)} token so the UI login/logout lifecycle never
     * extends or revokes it.
     */
    private static final String CHANNEL_TOKEN_NAME = "DeepSQL Agent (channel)";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final McpTokenService mcpTokenService;

    public AgentBridgeService(McpTokenService mcpTokenService) {
        this.mcpTokenService = mcpTokenService;
    }

    /** Set false to disable per-user provisioning entirely (agent runs the shared default profile). */
    @Value("${agent.provision-enabled:true}")
    private boolean provisionEnabled;

    /**
     * The DeepSQL Agent provisioner endpoint.
     *
     * <p>Self-host Compose runs the {@code deepsql-agent} service, which exposes
     * a secret-gated {@code POST /provision} on :8788. The default matches that
     * compose-network hostname. For native (non-Compose) runs, set
     * {@code AGENT_PROVISIONER_URL=http://127.0.0.1:8788/provision} and start
     * {@code scripts/local-agent-provisioner.py}.
     *
     * <p>Provisioning is also gated on {@code provisionSecret}: when unset, no
     * request is sent and the Agent tab opens without a freshly minted per-user
     * profile.
     */
    @Value("${agent.provisioner-url:http://deepsql-agent:8788/provision}")
    private String provisionerUrl;

    /** Shared secret the agent provisioner requires — must match AGENT_PROVISION_SECRET on the agent. */
    @Value("${agent.provision-secret:}")
    private String provisionSecret;

    /**
     * The agent token is a sliding window anchored to the UI login session: it is
     * minted for this many days and bumped forward on every session refresh while
     * the user is active, then revoked on full logout. Defaults to the refresh
     * session lifetime so the agent stays usable for exactly as long as the user
     * can stay logged in — never less.
     */
    @Value("${security.session.refresh-days:7}")
    private long sessionWindowDays;

    public String profileFor(String username) {
        String safe = username.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return "u-" + safe;
    }

    /**
     * Ensure the user's agent profile exists and is bound to their current token;
     * returns the profile name. Best-effort — provisioning problems are logged but
     * never thrown (the Agent tab still opens, just without fresh per-user scope).
     */
    public String ensureProfile(String username, String authToken, String connectionId) {
        String profile = profileFor(username);
        if (!provisionEnabled) {
            return profile;
        }
        if (provisionSecret == null || provisionSecret.isBlank()) {
            log.warn("agent.provision-secret is unset — skipping per-user provisioning for {}", username);
            return profile;
        }
        // The agent profile must carry a credential that outlives a single chat
        // session. The user's session JWT lives only ~15 min and is coupled to a
        // server-side session, so a long chat (or a re-opened tab) would 401 on
        // every MCP call. Mint a dedicated, user-scoped, revocable MCP token
        // instead (authenticated by McpTokenAuthenticationFilter, not the session
        // filter). Fall back to the session token only if minting fails so the
        // tab still opens.
        String agentToken = mintAgentToken(username);
        if (agentToken == null) {
            agentToken = authToken == null ? "" : authToken;
        }
        callProvisioner(username, profile, agentToken, connectionId);
        return profile;
    }

    /**
     * Headless variant for non-browser channels (Slack, etc.): provision the
     * user's profile with a dedicated CHANNEL token minted directly for the
     * DeepSQL user — no inbound session/cookie needed. The channel token has its
     * own name so the UI login/logout lifecycle (which extends/revokes the
     * {@code (auto)} token) never touches it; a web logout won't kill the user's
     * Slack agent access. Best-effort — never throws.
     */
    public String ensureProfileForUser(String username, String connectionId) {
        String profile = profileFor(username);
        if (!provisionEnabled) {
            return profile;
        }
        if (provisionSecret == null || provisionSecret.isBlank()) {
            log.warn("agent.provision-secret is unset — skipping headless provisioning for {}", username);
            return profile;
        }
        String channelToken = mintChannelToken(username);
        if (channelToken == null) {
            log.warn("Could not mint channel token for {} — skipping headless provisioning", username);
            return profile;
        }
        callProvisioner(username, profile, channelToken, connectionId);
        return profile;
    }

    /** POST the provision request to the agent container's internal provisioner. */
    private void callProvisioner(String username, String profile, String token, String connectionId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "user", username,
                "token", token == null ? "" : token,
                "connectionId", connectionId == null ? "" : connectionId));
            HttpRequest req = HttpRequest.newBuilder(URI.create(provisionerUrl))
                .header("Content-Type", "application/json")
                .header("X-Provision-Secret", provisionSecret)
                .timeout(Duration.ofSeconds(190))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                log.info("Provisioned/refreshed agent profile {} for user {}", profile, username);
            } else {
                log.warn("Agent provisioning HTTP {} for user {}: {}", resp.statusCode(), username, resp.body());
            }
        } catch (Exception e) {
            log.warn("Agent provisioning call failed for user {}: {}", username, e.getMessage());
        }
    }

    /**
     * Mint a fresh dedicated MCP token for the user and return its plaintext, or
     * {@code null} if minting failed (e.g. user not resolvable) so the caller can
     * fall back to the session token. Best-effort — never throws.
     *
     * <p>We deliberately do NOT revoke the user's prior agent tokens here. A user
     * may have more than one live Agent surface (a second browser tab, an in-flight
     * chat whose MCP process already read an earlier token); revoking on open would
     * 401 those while the UI is still logged in. Old tokens stay valid, ride the
     * same sliding extension on each session refresh, and are all cleaned up
     * together on logout. The handful of extra rows per login is negligible.
     */
    private String mintAgentToken(String username) {
        try {
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(sessionWindowDays);
            McpTokenService.CreatedToken created =
                mcpTokenService.createTokenForUser(username, AGENT_TOKEN_NAME, expiresAt);
            log.info("Minted dedicated agent MCP token {} for user {} (window {}d)",
                created.token().getId(), username, sessionWindowDays);
            return created.plainTextToken();
        } catch (Exception e) {
            log.warn("Could not mint agent MCP token for {} — falling back to session token: {}",
                username, e.getMessage());
            return null;
        }
    }

    /** Mint a dedicated CHANNEL token (Slack, etc.); null if minting failed. */
    private String mintChannelToken(String username) {
        try {
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(sessionWindowDays);
            McpTokenService.CreatedToken created =
                mcpTokenService.createTokenForUser(username, CHANNEL_TOKEN_NAME, expiresAt);
            log.info("Minted channel MCP token {} for user {} (window {}d)",
                created.token().getId(), username, sessionWindowDays);
            return created.plainTextToken();
        } catch (Exception e) {
            log.warn("Could not mint channel MCP token for {}: {}", username, e.getMessage());
            return null;
        }
    }

    /**
     * Keep the user's agent token(s) alive for as long as their UI session lives.
     * Call on each successful session login/refresh: it slides every active agent
     * token's expiry forward to {@code now + sessionWindowDays}, so an actively
     * used UI never has a dead agent. No-op (cheap) if the user has no agent token
     * yet (they haven't opened the Agent tab). Best-effort — never throws.
     */
    public void extendAgentTokens(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(sessionWindowDays);
            int n = mcpTokenService.extendActiveTokensByName(username, AGENT_TOKEN_NAME, expiresAt);
            if (n > 0) {
                log.debug("Extended {} agent token(s) for user {} to {}", n, username, expiresAt);
            }
        } catch (Exception e) {
            log.warn("Could not extend agent token(s) for {}: {}", username, e.getMessage());
        }
    }

    /**
     * Revoke the user's agent token(s) — call when the user fully logs out (no
     * remaining active UI sessions). After this the agent stops working until the
     * user logs in and re-opens the Agent tab, which is the desired behaviour: no
     * usable agent credential should outlive the login that created it.
     * Best-effort — never throws.
     */
    public void revokeAgentTokens(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            int n = mcpTokenService.revokeActiveTokensByName(username, AGENT_TOKEN_NAME);
            if (n > 0) {
                log.info("Revoked {} agent token(s) for user {} on logout", n, username);
            }
        } catch (Exception e) {
            log.warn("Could not revoke agent token(s) for {}: {}", username, e.getMessage());
        }
    }
}
