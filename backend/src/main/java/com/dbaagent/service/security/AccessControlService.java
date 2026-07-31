package com.dbaagent.service.security;

import com.dbaagent.model.AnalysisHistory;
import com.dbaagent.model.Chat;
import com.dbaagent.model.ChatFeedback;
import com.dbaagent.model.EffectiveConnectionAccess;
import com.dbaagent.repository.AnalysisHistoryRepository;
import com.dbaagent.repository.ChatFeedbackRepository;
import com.dbaagent.repository.ChatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class AccessControlService {

    /**
     * Identity attributed to actions taken while {@code security.auth.enabled} is false.
     * Matches the owner fallback used when a connection is saved without a principal, so a
     * dev-mode install does not end up with records split across two synthetic owners.
     */
    private static final String LOCAL_FALLBACK_USERNAME = "admin";

    @Value("${security.auth.enabled:true}")
    private boolean authEnabled;

    private final ConnectionAccessService connectionAccessService;
    private final ChatRepository chatRepository;
    private final ChatFeedbackRepository chatFeedbackRepository;
    private final AnalysisHistoryRepository analysisHistoryRepository;

    public void assertCanAccessConnection(String connectionId) {
        assertCanUseConnection(connectionId);
    }

    public void assertCanUseConnection(String connectionId) {
        assertAccess(connectionId, EffectiveConnectionAccess::canUseConnection, "Access denied for this connection");
    }

    public void assertCanUseChatEditor(String connectionId) {
        assertAccess(connectionId, EffectiveConnectionAccess::canUseChatEditor, "Chat and editor access denied for this connection");
    }

    public void assertCanManageConnectionContent(String connectionId) {
        assertAccess(connectionId, EffectiveConnectionAccess::canManageContent, "Content access denied for this connection");
    }

    /**
     * Read-only access to connection content (brain context, knowledge,
     * analytics, recommendations). Any user with connection access (CHAT_EDITOR
     * or higher) can read; write paths still require canManageContent.
     */
    public void assertCanReadConnectionContent(String connectionId) {
        assertAccess(connectionId, EffectiveConnectionAccess::canReadContent, "Read access denied for this connection");
    }

    public void assertCanManageConnectionConfig(String connectionId) {
        assertAccess(connectionId, EffectiveConnectionAccess::canManageConfig, "Configuration access denied for this connection");
    }

    public ConnectionAccessService.ResolvedConnectionAccess resolveCurrentUserAccess(String connectionId) {
        if (!authEnabled) {
            return connectionAccessService.resolveAccess(connectionId, null, true);
        }
        Authentication authentication = currentAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(FORBIDDEN, "Access denied");
        }
        try {
            return connectionAccessService.resolveAccess(connectionId, authentication.getName(), isCurrentUserAdmin());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(NOT_FOUND, "Connection not found");
        }
    }

    public void assertCanAccessChat(String chatId) {
        Chat chat = findAccessibleChat(chatId);
        assertCanUseChatEditor(chat.getConnectionId());
    }

    public void assertChatBelongsToConnection(String chatId, String connectionId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        Chat chat = findAccessibleChat(chatId);
        if (!connectionId.equals(chat.getConnectionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Chat does not belong to the selected connection");
        }
        assertCanUseChatEditor(connectionId);
    }

    /**
     * As {@link #assertChatBelongsToConnection}, but tolerates a chat that does not
     * exist yet.
     *
     * <p>Clients generate a chat id when the user opens a conversation and send it with
     * the first message, before anything is persisted. Treating that as "Chat not found"
     * rejected the opening message of every new conversation — which is what this
     * variant existed to prevent, except its body was identical to the strict one, so
     * the name promised leniency the code never implemented.
     *
     * <p>The existence check must come first, and must not be folded into
     * {@code findAccessibleChatIfPresent}: that method looks chats up by id AND owner,
     * so it returns empty both for "no such chat" and for "someone else's chat".
     * Collapsing the two would turn this into a probe — pass another user's chat id, get
     * an empty result, sail through. So: absent means there is nothing to protect and we
     * allow; present means the strict rules apply, unchanged.
     */
    public void assertChatBelongsToConnectionIfPresent(String chatId, String connectionId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        if (!chatRepository.existsById(chatId)) {
            return;
        }
        assertChatBelongsToConnection(chatId, connectionId);
    }

    public void assertCanAccessFeedback(String feedbackId) {
        ChatFeedback feedback = chatFeedbackRepository.findById(feedbackId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Feedback not found"));
        if (feedback.getChatId() != null && !feedback.getChatId().isBlank()) {
            assertCanAccessChat(feedback.getChatId());
            return;
        }
        assertCanUseChatEditor(feedback.getConnectionId());
    }

    public void assertCanAccessAnalysisHistory(String historyId) {
        AnalysisHistory history = analysisHistoryRepository.findById(historyId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Analysis history not found"));
        assertCanUseChatEditor(history.getConnectionId());
    }

    public String getCurrentUsername() {
        Authentication authentication = currentAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }

    /**
     * The caller's username, or the local fallback identity when authentication is off.
     *
     * <p>Every other check in this class honours {@code security.auth.enabled} — with auth
     * off, {@link #resolveCurrentUserAccess} hands back ADMIN and {@link #isCurrentUserAdmin}
     * returns true. This method did not, so it threw 403 "Access denied" at all 17 of its
     * call sites the moment anyone actually ran with auth disabled, {@code ChatController}
     * included: the documented dev-mode bypass switched chat off instead of opening it.
     * A bypass has to be coherent or it is not a bypass.
     *
     * <p>{@code "admin"} matches the owner fallback already used when a connection is saved
     * without a principal, so records created in dev mode carry one consistent owner.
     *
     * <p>With auth enabled — every real deployment — behaviour is unchanged: no principal
     * means 403.
     */
    public String requireCurrentUsername() {
        String username = getCurrentUsername();
        if (username == null || username.isBlank()) {
            if (!authEnabled) {
                return LOCAL_FALLBACK_USERNAME;
            }
            throw new ResponseStatusException(FORBIDDEN, "Access denied");
        }
        return username;
    }

    public boolean isCurrentUserAdmin() {
        if (!authEnabled) {
            return true;
        }
        Authentication authentication = currentAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private Chat findAccessibleChat(String chatId) {
        return findAccessibleChatIfPresent(chatId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Chat not found"));
    }

    private Optional<Chat> findAccessibleChatIfPresent(String chatId) {
        if (!authEnabled) {
            return chatRepository.findById(chatId);
        }
        String username = requireCurrentUsername();
        return chatRepository.findByIdAndOwnerUsernameIgnoreCase(chatId, username);
    }

    private void assertAccess(
        String connectionId,
        java.util.function.Predicate<EffectiveConnectionAccess> predicate,
        String message
    ) {
        ConnectionAccessService.ResolvedConnectionAccess access = resolveCurrentUserAccess(connectionId);
        if (!predicate.test(access.getEffectiveAccess())) {
            throw new ResponseStatusException(FORBIDDEN, message);
        }
    }
}
