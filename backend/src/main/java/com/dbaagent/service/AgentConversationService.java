package com.dbaagent.service;

import com.dbaagent.model.AgentConversation;
import com.dbaagent.model.User;
import com.dbaagent.repository.AgentConversationRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.security.AccessControlService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns the per-user index of DeepSQL Agent conversations so a user can resume
 * their chats from any device. Every operation is scoped to the authenticated
 * user — conversations are never visible or writable across users.
 */
@Service
@RequiredArgsConstructor
public class AgentConversationService {

    private final AgentConversationRepository repository;
    private final UserRepository userRepository;
    private final AccessControlService accessControlService;

    private Long currentUserId() {
        String username = accessControlService.requireCurrentUsername();
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        return user.getId();
    }

    private static String normalizeConnectionId(String connectionId) {
        return (connectionId == null || connectionId.isBlank()) ? null : connectionId;
    }

    /** The current user's non-archived conversations for a connection, newest first. */
    @Transactional(readOnly = true)
    public List<AgentConversation> list(String connectionId) {
        Long userId = currentUserId();
        String conn = normalizeConnectionId(connectionId);
        return conn == null
            ? repository.findByUserIdAndConnectionIdIsNullAndArchivedFalseOrderByLastMessageAtDesc(userId)
            : repository.findByUserIdAndConnectionIdAndArchivedFalseOrderByLastMessageAtDesc(userId, conn);
    }

    /** Fetch one conversation, 404 if it isn't the current user's. */
    @Transactional(readOnly = true)
    public AgentConversation get(String id) {
        return repository.findByIdAndUserId(id, currentUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    /** Create a conversation bound to the current user + agent session. */
    @Transactional
    public AgentConversation create(String connectionId, String agentSessionId, String title) {
        if (agentSessionId == null || agentSessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentSessionId is required");
        }
        AgentConversation c = new AgentConversation();
        c.setUserId(currentUserId());
        c.setConnectionId(normalizeConnectionId(connectionId));
        c.setAgentSessionId(agentSessionId);
        c.setTitle(trimTitle(title));
        c.setLastMessageAt(LocalDateTime.now());
        return repository.save(c);
    }

    /**
     * Update a conversation's transcript/title and (optionally) re-point it at a
     * new agent session — the panel does this when it has to spin up a fresh
     * session because the old one was lost (e.g. an agent redeploy).
     */
    @Transactional
    public AgentConversation update(String id, JsonNode transcript, String title, String agentSessionId) {
        AgentConversation c = get(id); // ownership-checked
        if (transcript != null) c.setTranscript(transcript);
        if (title != null && !title.isBlank()) c.setTitle(trimTitle(title));
        if (agentSessionId != null && !agentSessionId.isBlank()) c.setAgentSessionId(agentSessionId);
        c.setLastMessageAt(LocalDateTime.now());
        return repository.save(c);
    }

    /** Soft-delete (archive) so it drops out of the list but history is retained. */
    @Transactional
    public void archive(String id) {
        AgentConversation c = get(id);
        c.setArchived(true);
        repository.save(c);
    }

    private static String trimTitle(String title) {
        if (title == null) return null;
        String t = title.trim();
        if (t.isEmpty()) return null;
        return t.length() > 255 ? t.substring(0, 255) : t;
    }
}
