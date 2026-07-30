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

    public void assertChatBelongsToConnectionIfPresent(String chatId, String connectionId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        Chat chat = findAccessibleChat(chatId);
        if (!connectionId.equals(chat.getConnectionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Chat does not belong to the selected connection");
        }
        assertCanUseChatEditor(connectionId);
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

    public String requireCurrentUsername() {
        String username = getCurrentUsername();
        if (username == null || username.isBlank()) {
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
