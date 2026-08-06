package com.dbaagent.service;

import com.dbaagent.model.AgentConversation;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs one DeepSQL Agent turn on behalf of the authenticated user and returns
 * the final answer. This is the shared, identity-scoped broker used by
 * non-browser clients (the thin `deepsql` CLI; the same shape Slack uses): the
 * caller never names a profile or session — those are derived from the user's
 * token — so a user can only ever drive their own agent.
 *
 * <p>Reuses the per-user SSO bridge ({@link AgentBridgeService}), the agent webui
 * client ({@link AgentChatClient}), and the cross-device conversation index
 * ({@link AgentConversationService}) so a CLI turn shares the same server-side
 * agent session — and history — as the web Agent tab.
 */
@Service
@RequiredArgsConstructor
public class AgentTurnService {

    private final AccessControlService accessControlService;
    private final AgentBridgeService agentBridgeService;
    private final AgentChatClient agentChatClient;
    private final AgentConversationService conversationService;

    public record TurnResult(boolean ok, String answer, List<String> toolSteps,
                             String conversationId, String error) {}

    /**
     * Resume (or start) the user's conversation for a connection and run one turn.
     * @param connectionId    active connection (may be null)
     * @param conversationId  explicit conversation to continue, or null to resume the latest / start fresh
     * @param message         the user's message
     */
    public TurnResult runTurn(String connectionId, String conversationId, String message) {
        String username = accessControlService.requireCurrentUsername();
        String profile = agentBridgeService.ensureProfileForUser(username, connectionId);

        AgentConversation conv;
        boolean firstTurn;
        if (conversationId != null && !conversationId.isBlank()) {
            conv = conversationService.get(conversationId); // ownership-checked (404 if not the user's)
            firstTurn = isEmptyTranscript(conv);
        } else {
            List<AgentConversation> existing = conversationService.list(connectionId);
            conv = existing.isEmpty() ? null : existing.get(0); // newest first
            firstTurn = conv == null || isEmptyTranscript(conv);
        }

        String existingSession = conv == null ? null : conv.getAgentSessionId();
        // Detailed variant: "unavailable right now" reads as a transient outage and sends
        // people away to wait, when the real causes — the runtime is not running, the URL
        // is wrong, or it demands a login the backend never performs — stay broken until
        // somebody acts. Report what actually happened.
        AgentChatClient.SessionAttempt attempt =
            agentChatClient.ensureSessionDetailed(profile, existingSession);
        if (attempt.sessionId() == null) {
            return new TurnResult(false, null, List.of(), conv == null ? null : conv.getId(),
                "DeepSQL agent unavailable: " + attempt.failureReason());
        }
        String sessionId = attempt.sessionId();

        if (conv == null) {
            conv = conversationService.create(connectionId, sessionId, deriveTitle(message));
        } else if (!sessionId.equals(conv.getAgentSessionId())) {
            conversationService.update(conv.getId(), null, null, sessionId);
        }

        String toSend = firstTurn ? withConnectionContext(message, connectionId) : message;
        AgentChatClient.AgentReply reply = agentChatClient.sendAndAwait(sessionId, toSend);

        if (!reply.ok()) {
            // The reused session may be gone (agent redeployed) — fresh one + retry once.
            String fresh = agentChatClient.ensureSession(profile, null);
            if (fresh != null) {
                conversationService.update(conv.getId(), null, null, fresh);
                reply = agentChatClient.sendAndAwait(fresh, withConnectionContext(message, connectionId));
            }
        }
        if (!reply.ok()) {
            return new TurnResult(false, null, List.of(), conv.getId(),
                reply.error() == null ? "the agent run ended early" : reply.error());
        }
        return new TurnResult(true, reply.text(), reply.toolSteps(), conv.getId(), null);
    }

    private static boolean isEmptyTranscript(AgentConversation conv) {
        return conv.getTranscript() == null || conv.getTranscript().isEmpty();
    }

    private static String withConnectionContext(String message, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return message;
        }
        return "[Active DeepSQL connection: id " + connectionId
            + ". Use this connection unless I name another.]\n\n" + message;
    }

    private static String deriveTitle(String message) {
        if (message == null) return null;
        String t = message.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
