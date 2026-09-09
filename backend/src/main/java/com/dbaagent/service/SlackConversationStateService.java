package com.dbaagent.service;

import com.dbaagent.model.Chat;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.SlackChannelBinding;
import com.dbaagent.model.SlackEventReceipt;
import com.dbaagent.model.SlackThreadSession;
import com.dbaagent.model.User;
import com.dbaagent.repository.SlackChannelBindingRepository;
import com.dbaagent.repository.SlackEventReceiptRepository;
import com.dbaagent.repository.SlackThreadSessionRepository;
import com.dbaagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SlackConversationStateService {

    private final SlackRuntimeSettingsService slackRuntimeSettingsService;
    private final SlackChannelBindingRepository channelBindingRepository;
    private final SlackThreadSessionRepository threadSessionRepository;
    private final SlackEventReceiptRepository eventReceiptRepository;
    private final CredentialService credentialService;
    private final ChatHistoryService chatHistoryService;
    private final UserRepository userRepository;

    @Transactional
    public SlackChannelBinding bindDefaultConnection(
        String teamId,
        String channelId,
        String channelType,
        String connectionReference,
        String updatedBy
    ) {
        DatabaseConnection connection = resolveAllowedConnection(connectionReference);
        SlackChannelBinding binding = upsertBinding(
            teamId,
            channelId,
            channelType,
            connection,
            updatedBy
        );
        return binding;
    }

    @Transactional
    public ConnectionBindingResolution bindConversation(
        String teamId,
        String channelId,
        String channelType,
        String rootThreadTs,
        String connectionReference,
        String updatedBy
    ) {
        DatabaseConnection connection = resolveAllowedConnection(connectionReference);
        SlackChannelBinding binding = upsertBinding(
            teamId,
            channelId,
            channelType,
            connection,
            updatedBy
        );

        boolean threadReset = false;
        String chatId = null;
        if (rootThreadTs != null && !rootThreadTs.isBlank()) {
            SlackThreadSession session = threadSessionRepository.findByTeamIdAndChannelIdAndRootThreadTs(
                teamId,
                channelId,
                rootThreadTs
            ).orElseGet(SlackThreadSession::new);

            session.setTeamId(teamId);
            session.setChannelId(channelId);
            session.setRootThreadTs(rootThreadTs);

            if (session.getChatId() == null
                || session.getChatId().isBlank()
                || !connection.getId().equalsIgnoreCase(session.getConnectionId())) {
                Chat chat = chatHistoryService.createChat(connection.getId(), null, "New chat", serviceUsername());
                session.setConnectionId(connection.getId());
                session.setChatId(chat.getId());
                threadReset = true;
            }

            session.setLastUsedAt(LocalDateTime.now());
            SlackThreadSession saved = threadSessionRepository.save(session);
            chatId = saved.getChatId();
        }

        return new ConnectionBindingResolution(
            connection.getId(),
            connection.getConnectionName(),
            binding.getChannelType(),
            chatId,
            threadReset
        );
    }

    private SlackChannelBinding upsertBinding(
        String teamId,
        String channelId,
        String channelType,
        DatabaseConnection connection,
        String updatedBy
    ) {
        SlackChannelBinding binding = channelBindingRepository.findByTeamIdAndChannelId(teamId, channelId)
            .orElseGet(SlackChannelBinding::new);
        binding.setTeamId(teamId);
        binding.setChannelId(channelId);
        binding.setChannelType(normalizeChannelType(channelType));
        binding.setDefaultConnectionId(connection.getId());
        binding.setUpdatedBy(updatedBy);
        return channelBindingRepository.save(binding);
    }

    @Transactional
    public void clearBinding(String teamId, String channelId) {
        channelBindingRepository.deleteByTeamIdAndChannelId(teamId, channelId);
    }

    @Transactional(readOnly = true)
    public Optional<SlackChannelBinding> findBinding(String teamId, String channelId) {
        return channelBindingRepository.findByTeamIdAndChannelId(teamId, channelId);
    }

    @Transactional
    public boolean markEventReceived(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        if (eventReceiptRepository.existsById(eventId)) {
            return false;
        }
        SlackEventReceipt receipt = new SlackEventReceipt();
        receipt.setEventId(eventId);
        receipt.setReceivedAt(LocalDateTime.now());
        eventReceiptRepository.save(receipt);
        return true;
    }

    @Transactional
    public ThreadSessionResolution resolveThreadSession(
        String teamId,
        String channelId,
        String channelType,
        String rootThreadTs,
        String openingMessage
    ) {
        SlackChannelBinding binding = channelBindingRepository.findByTeamIdAndChannelId(teamId, channelId)
            .orElseThrow(() -> new IllegalArgumentException(
                "No default DeepSQL connection is set for this conversation. Use /deepsql-use <connection> or say `use <connection>` first."
            ));

        SlackThreadSession session = threadSessionRepository.findByTeamIdAndChannelIdAndRootThreadTs(
            teamId,
            channelId,
            rootThreadTs
        ).orElseGet(() -> createThreadSession(binding, rootThreadTs, openingMessage));

        session.setChannelId(channelId);
        session.setTeamId(teamId);
        session.setLastUsedAt(LocalDateTime.now());
        threadSessionRepository.save(session);

        return new ThreadSessionResolution(
            session.getConnectionId(),
            session.getChatId(),
            binding.getChannelType()
        );
    }

    @Transactional
    public ThreadSessionResolution resolveLinkedThreadSession(
        String teamId,
        String channelId,
        String channelType,
        String rootThreadTs,
        String openingMessage,
        String slackUserId,
        String deepsqlUsername,
        String connectionId
    ) {
        SlackThreadSession session = threadSessionRepository.findByTeamIdAndChannelIdAndRootThreadTs(
            teamId,
            channelId,
            rootThreadTs
        ).orElseGet(SlackThreadSession::new);

        boolean needsFreshChat = session.getChatId() == null
            || session.getChatId().isBlank()
            || session.getConnectionId() == null
            || !session.getConnectionId().equalsIgnoreCase(connectionId)
            || session.getSlackUserId() == null
            || !session.getSlackUserId().equals(slackUserId);

        if (needsFreshChat) {
            Chat chat = chatHistoryService.createChatFromFirstMessage(
                connectionId,
                null,
                openingMessage,
                deepsqlUsername
            );
            session.setConnectionId(connectionId);
            session.setChatId(chat.getId());
        }

        session.setTeamId(teamId);
        session.setChannelId(channelId);
        session.setRootThreadTs(rootThreadTs);
        session.setSlackUserId(slackUserId);
        session.setDeepsqlUsername(deepsqlUsername);
        session.setLastUsedAt(LocalDateTime.now());
        SlackThreadSession saved = threadSessionRepository.save(session);

        return new ThreadSessionResolution(
            saved.getConnectionId(),
            saved.getChatId(),
            normalizeChannelType(channelType)
        );
    }

    /** The DeepSQL Agent session id bound to a Slack thread, if any (slack.brain=agent). */
    @Transactional(readOnly = true)
    public Optional<String> getAgentSessionId(String teamId, String channelId, String rootThreadTs) {
        return threadSessionRepository.findByTeamIdAndChannelIdAndRootThreadTs(teamId, channelId, rootThreadTs)
            .map(SlackThreadSession::getAgentSessionId)
            .filter(s -> s != null && !s.isBlank());
    }

    /** Persist the DeepSQL Agent session id for a Slack thread (created lazily on first turn). */
    @Transactional
    public void setAgentSessionId(String teamId, String channelId, String rootThreadTs, String agentSessionId) {
        threadSessionRepository.findByTeamIdAndChannelIdAndRootThreadTs(teamId, channelId, rootThreadTs)
            .ifPresent(session -> {
                session.setAgentSessionId(agentSessionId);
                threadSessionRepository.save(session);
            });
    }

    @Transactional(readOnly = true)
    public StatusSnapshot buildStatusSnapshot() {
        SlackRuntimeSettingsService.SlackRuntimeConfig config = slackRuntimeSettingsService.current();
        String serviceUsername = config.deepsqlBotUsername();
        Optional<User> serviceUser = resolveServiceUser();
        List<DatabaseConnection> allowedConnections = allowedConnections();

        return new StatusSnapshot(
            config.enabled(),
            config.socketModeEnabled(),
            hasText(config.appToken()),
            hasText(config.botToken()),
            hasText(config.signingSecret()),
            serviceUsername,
            serviceUser.isPresent(),
            serviceUser.map(User::getUsername).orElse(null),
            // getRoleCode, not getRoleEnum().name(): the enum is null for a custom role
            // and this status snapshot would NPE on it.
            serviceUser.map(User::getRoleCode).orElse(null),
            serviceUser.map(User::isAdmin).orElse(false),
            allowedConnections.size(),
            Math.toIntExact(channelBindingRepository.count()),
            Math.toIntExact(threadSessionRepository.count()),
            Math.toIntExact(eventReceiptRepository.count()),
            allowedConnections.stream()
                .map(connection -> connection.getConnectionName() + " (" + connection.getId() + ")")
                .toList()
        );
    }

    @Transactional(readOnly = true)
    public List<DatabaseConnection> allowedConnections() {
        String serviceUsername = slackRuntimeSettingsService.current().deepsqlBotUsername();
        if (serviceUsername == null || serviceUsername.isBlank()) {
            return List.of();
        }
        boolean isAdmin = userRepository.findByUsername(serviceUsername)
            .map(User::isAdmin)
            .orElse(false);
        return credentialService.getConnectionsForUser(serviceUsername, isAdmin);
    }

    @Transactional(readOnly = true)
    public DatabaseConnection resolveAllowedConnection(String connectionReference) {
        String normalized = connectionReference == null ? "" : connectionReference.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Connection name or ID is required.");
        }

        return allowedConnections().stream()
            .filter(connection -> normalized.equalsIgnoreCase(connection.getId())
                || normalized.equalsIgnoreCase(connection.getConnectionName()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown Slack connection `" + normalized + "`. " + availableConnectionsHint()
            ));
    }

    @Transactional(readOnly = true)
    public Optional<User> resolveServiceUser() {
        String username = slackRuntimeSettingsService.current().deepsqlBotUsername();
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByUsername(username);
    }

    private SlackThreadSession createThreadSession(
        SlackChannelBinding binding,
        String rootThreadTs,
        String openingMessage
    ) {
        Chat chat = chatHistoryService.createChatFromFirstMessage(
            binding.getDefaultConnectionId(),
            null,
            openingMessage,
            serviceUsername()
        );

        SlackThreadSession session = new SlackThreadSession();
        session.setTeamId(binding.getTeamId());
        session.setChannelId(binding.getChannelId());
        session.setRootThreadTs(rootThreadTs);
        session.setConnectionId(binding.getDefaultConnectionId());
        session.setChatId(chat.getId());
        return session;
    }

    private String serviceUsername() {
        String username = slackRuntimeSettingsService.current().deepsqlBotUsername();
        return (username == null || username.isBlank()) ? null : username.trim();
    }

    private String availableConnectionsHint() {
        List<DatabaseConnection> connections = allowedConnections();
        if (connections.isEmpty()) {
            return "No Slack-owned DeepSQL connections are available for the configured service user.";
        }
        String preview = connections.stream()
            .limit(10)
            .map(connection -> connection.getConnectionName() + " (" + connection.getId() + ")")
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
        return "Available connections: " + preview;
    }

    private String normalizeChannelType(String channelType) {
        if (channelType == null || channelType.isBlank()) {
            return "unknown";
        }
        return channelType.toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ThreadSessionResolution(
        String connectionId,
        String chatId,
        String channelType
    ) {
    }

    public record ConnectionBindingResolution(
        String connectionId,
        String connectionName,
        String channelType,
        String chatId,
        boolean threadReset
    ) {
    }

    public record StatusSnapshot(
        boolean enabled,
        boolean socketModeEnabled,
        boolean appTokenConfigured,
        boolean botTokenConfigured,
        boolean signingSecretConfigured,
        String configuredBotUsername,
        boolean resolvedBotExists,
        String resolvedBotUsername,
        String resolvedBotRole,
        boolean resolvedBotIsAdmin,
        int allowedConnectionCount,
        int bindingCount,
        int threadSessionCount,
        int eventReceiptCount,
        List<String> allowedConnections
    ) {
    }
}
