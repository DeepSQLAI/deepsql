package com.dbaagent.service;

import com.dbaagent.dto.SlackLinkedConnectionResponse;
import com.dbaagent.model.ChatResponse;
import com.dbaagent.model.DatabaseConnection;
import com.slack.api.Slack;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.jakarta_socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlackBotService {

    private static final Pattern LEADING_MENTION_PATTERN = Pattern.compile("^(?:\\s*<@[^>]+>\\s*)+");
    private static final Pattern USE_CONNECTION_PATTERN = Pattern.compile(
        "(?i)^(?:use|switch(?:\\s+to)?|set(?:\\s+(?:connection|database))?\\s+to|connect(?:\\s+to)?)\\s+(.+?)\\s*$"
    );
    private static final Pattern RESET_CONNECTION_PATTERN = Pattern.compile(
        "(?i)^(?:reset|clear)(?:\\s+(?:connection|context|binding))?\\s*$"
    );
    private static final Pattern LINK_CODE_PATTERN = Pattern.compile(
        "(?i)^(?:link|code)\\s+([a-z0-9-]+)\\s*$"
    );
    private static final Pattern UNLINK_PATTERN = Pattern.compile(
        "(?i)^(?:unlink|disconnect)\\s*$"
    );

    private final SlackRuntimeSettingsService slackRuntimeSettingsService;
    private final SlackConversationStateService conversationStateService;
    private final SlackUserLinkService slackUserLinkService;
    private final ChatService chatService;
    private final AgentChatClient agentChatClient;
    private final AgentBridgeService agentBridgeService;

    /** Which brain Slack conversations use: "chat" (legacy ChatService) or "agent" (DeepSQL Agent). */
    @org.springframework.beans.factory.annotation.Value("${slack.brain:chat}")
    private String slackBrain;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile SocketModeApp socketModeApp;
    private volatile boolean started;
    private volatile boolean running;
    private volatile String lastError;
    private volatile String botUserId;

    @EventListener(ApplicationReadyEvent.class)
    public void startIfConfigured() {
        refreshFromConfig();
    }

    public synchronized void refreshFromConfig() {
        SlackRuntimeSettingsService.SlackRuntimeConfig config = slackRuntimeSettingsService.current();
        stopCurrentApp();

        if (!config.enabled() || !config.socketModeEnabled()) {
            started = false;
            running = false;
            lastError = null;
            botUserId = null;
            log.info("Slack Socket Mode bot is disabled by runtime configuration");
            return;
        }
        if (!hasRequiredConfig(config)) {
            started = false;
            running = false;
            botUserId = null;
            lastError = "Slack is enabled but required tokens are missing.";
            log.warn(lastError);
            return;
        }

        try {
            AppConfig appConfig = AppConfig.builder()
                .singleTeamBotToken(config.botToken())
                .signingSecret(config.signingSecret())
                .build();

            App app = new App(appConfig);
            registerHandlers(app);

            socketModeApp = new SocketModeApp(config.appToken(), app);
            socketModeApp.startAsync();
            botUserId = resolveBotUserId(config);
            started = true;
            running = true;
            lastError = null;
            log.info("Slack Socket Mode bot started with linked-user DeepSQL auth");
        } catch (Exception e) {
            running = false;
            started = false;
            botUserId = null;
            lastError = e.getMessage();
            log.error("Failed to start Slack Socket Mode bot", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        stopCurrentApp();
        executor.shutdownNow();
    }

    public StatusSnapshot status() {
        SlackConversationStateService.StatusSnapshot configStatus = conversationStateService.buildStatusSnapshot();
        return new StatusSnapshot(
            started,
            running,
            lastError,
            botUserId,
            configStatus
        );
    }

    private void registerHandlers(App app) {
        app.command("/deepsql-use", (req, ctx) -> {
            String connectionReference = req.getPayload().getText();
            String teamId = req.getPayload().getTeamId();
            String channelType = inferChannelType(req.getPayload().getChannelId());
            if (!isDirectMessage(channelType)) {
                return ctx.ack("For secure DB access, please DM me, link your DeepSQL account, and run `use <connection>` there.");
            }
            try {
                SlackUserLinkService.LinkedUser linkedUser = slackUserLinkService.resolveLinkedUser(teamId, req.getPayload().getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Link your DeepSQL account first. In a DM, send `link <your-access-code>`."));
                DatabaseConnection connection = slackUserLinkService.bindDefaultConnection(
                    teamId,
                    req.getPayload().getUserId(),
                    linkedUser.username(),
                    linkedUser.admin(),
                    connectionReference
                );
                return ctx.ack("DeepSQL is now using `" + connection.getConnectionName() + "` for your Slack session.");
            } catch (IllegalArgumentException e) {
                return ctx.ack(e.getMessage());
            }
        });

        app.command("/deepsql-reset", (req, ctx) -> {
            if (!isDirectMessage(inferChannelType(req.getPayload().getChannelId()))) {
                return ctx.ack("Please DM me to manage your DeepSQL Slack connection.");
            }
            slackUserLinkService.clearDefaultConnection(req.getPayload().getTeamId(), req.getPayload().getUserId());
            return ctx.ack("Cleared your default DeepSQL connection for Slack.");
        });

        app.command("/deepsql-help", (req, ctx) -> ctx.ack(buildHelpMessage(
            req.getPayload().getTeamId(),
            req.getPayload().getUserId()
        )));

        app.event(AppMentionEvent.class, (req, ctx) -> {
            if (!conversationStateService.markEventReceived(req.getEventId())) {
                return ctx.ack();
            }
            AppMentionEvent event = req.getEvent();
            executor.submit(() -> handleConversation(
                req.getTeamId(),
                event.getChannel(),
                inferChannelType(event.getChannel()),
                event.getThreadTs() != null ? event.getThreadTs() : event.getTs(),
                event.getUser(),
                stripLeadingMention(event.getText())
            ));
            return ctx.ack();
        });

        app.event(MessageEvent.class, (req, ctx) -> {
            MessageEvent event = req.getEvent();
            String inferredChannelType = event.getChannelType() != null && !event.getChannelType().isBlank()
                ? event.getChannelType()
                : inferChannelType(event.getChannel());
            if (!isSupportedDirectMessage(event, inferredChannelType)) {
                return ctx.ack();
            }
            if (!conversationStateService.markEventReceived(req.getEventId())) {
                return ctx.ack();
            }
            executor.submit(() -> handleConversation(
                req.getTeamId(),
                event.getChannel(),
                inferredChannelType,
                event.getThreadTs() != null ? event.getThreadTs() : event.getTs(),
                event.getUser(),
                event.getText()
            ));
            return ctx.ack();
        });
    }

    private void handleConversation(
        String teamId,
        String channelId,
        String channelType,
        String rootThreadTs,
        String userId,
        String message
    ) {
        String trimmedMessage = message == null ? "" : message.trim();
        if (!isDirectMessage(channelType)) {
            postThreadMessage(
                channelId,
                rootThreadTs,
                "For secure database access, please DM me, link your DeepSQL account with `link <access-code>`, then choose a connection with `use <connection>`."
            );
            return;
        }
        if (trimmedMessage.isBlank()) {
            postThreadMessage(channelId, rootThreadTs, buildHelpMessage(teamId, userId));
            return;
        }

        Optional<String> linkResponse = handleLinkCommand(teamId, channelId, rootThreadTs, userId, trimmedMessage);
        if (linkResponse.isPresent()) {
            postThreadMessage(channelId, rootThreadTs, linkResponse.get());
            return;
        }

        Optional<SlackUserLinkService.LinkedUser> linkedUser = slackUserLinkService.resolveLinkedUser(teamId, userId);
        if (linkedUser.isEmpty()) {
            postThreadMessage(
                channelId,
                rootThreadTs,
                "Link your DeepSQL account first. In the DeepSQL UI, generate a Slack access code, then DM me `link <access-code>`."
            );
            return;
        }

        Optional<String> commandResponse = handlePlainLanguageConnectionCommand(
            teamId,
            channelId,
            channelType,
            rootThreadTs,
            userId,
            linkedUser.get(),
            trimmedMessage
        );
        if (commandResponse.isPresent()) {
            postThreadMessage(channelId, rootThreadTs, commandResponse.get());
            return;
        }

        try {
            String connectionId = slackUserLinkService.getDefaultConnectionId(teamId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Choose a connection first with `use <connection>` after linking your DeepSQL account."));
            var resolution = conversationStateService.resolveLinkedThreadSession(
                teamId,
                channelId,
                channelType,
                rootThreadTs,
                trimmedMessage,
                userId,
                linkedUser.get().username(),
                connectionId
            );

            postThreadMessage(channelId, rootThreadTs, "Working on it...");

            String reply;
            if ("agent".equalsIgnoreCase(slackBrain)) {
                reply = runAgentTurn(
                    linkedUser.get().username(),
                    resolution.connectionId(),
                    teamId,
                    channelId,
                    rootThreadTs,
                    trimmedMessage
                );
            } else {
                ChatResponse response = chatService.processMessage(
                    resolution.connectionId(),
                    trimmedMessage,
                    rootThreadTs,
                    linkedUser.get().username(),
                    resolution.chatId()
                );
                reply = formatChatResponse(response);
            }

            postThreadMessage(channelId, rootThreadTs, reply);
        } catch (IllegalArgumentException e) {
            postThreadMessage(channelId, rootThreadTs, e.getMessage());
        } catch (Exception e) {
            log.error("Slack DeepSQL conversation failed", e);
            postThreadMessage(
                channelId,
                rootThreadTs,
                "DeepSQL couldn't complete that request right now: " + e.getMessage()
            );
        }
    }

    Optional<String> handlePlainLanguageConnectionCommand(
        String teamId,
        String channelId,
        String channelType,
        String rootThreadTs,
        String userId,
        SlackUserLinkService.LinkedUser linkedUser,
        String message
    ) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String trimmed = message.trim();
        if (RESET_CONNECTION_PATTERN.matcher(trimmed).matches()) {
            slackUserLinkService.clearDefaultConnection(teamId, userId);
            return Optional.of("Cleared your DeepSQL default connection for Slack.");
        }

        var matcher = USE_CONNECTION_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String connectionReference = normalizeConnectionReference(matcher.group(1));
        if (connectionReference.isBlank()) {
            return Optional.of("Tell me which connection to use, for example: `use aws_sf_prod`.");
        }
        if (!isKnownConnectionReference(connectionReference)) {
            return Optional.empty();
        }

        try {
            DatabaseConnection connection = slackUserLinkService.bindDefaultConnection(
                teamId,
                userId,
                linkedUser.username(),
                linkedUser.admin(),
                connectionReference
            );
            return Optional.of("DeepSQL is now using `" + connection.getConnectionName() + "` for your Slack session.");
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    private String buildHelpMessage(String teamId, String slackUserId) {
        Optional<SlackUserLinkService.LinkedUser> linkedUser = slackUserLinkService.resolveLinkedUser(teamId, slackUserId);
        if (linkedUser.isEmpty()) {
            return """
                DeepSQL Slack setup:
                • In the DeepSQL web app, generate a Slack access code
                • DM me: `link <access-code>`
                • Then choose a connection with `use <connection>`
                """.trim();
        }
        String binding = slackUserLinkService.getDefaultConnectionId(teamId, slackUserId).orElse("none");
        String availableConnections = slackUserLinkService.listVisibleConnections(linkedUser.get().username(), linkedUser.get().admin()).stream()
            .limit(10)
            .map(SlackLinkedConnectionResponse::getConnectionName)
            .reduce((left, right) -> left + ", " + right)
            .orElse("none");

        return """
            DeepSQL Slack bot commands:
            • `use <connection-name-or-id>` sets your default connection
            • `reset` clears the current connection
            • `unlink` disconnects your DeepSQL identity from Slack
            • `/deepsql-help` shows this help

            Current default connection: %s
            Available Slack connections: %s
            """.formatted(binding, availableConnections).trim();
    }

    private Optional<String> handleLinkCommand(
        String teamId,
        String channelId,
        String rootThreadTs,
        String slackUserId,
        String message
    ) {
        if (UNLINK_PATTERN.matcher(message).matches()) {
            slackUserLinkService.unlink(teamId, slackUserId);
            return Optional.of("Disconnected your DeepSQL account from Slack.");
        }
        var matcher = LINK_CODE_PATTERN.matcher(message.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            SlackUserLinkService.LinkedUser linkedUser = slackUserLinkService.consumeLinkCode(
                teamId,
                slackUserId,
                slackUserId,
                matcher.group(1)
            );
            String availableConnections = slackUserLinkService.listVisibleConnections(linkedUser.username(), linkedUser.admin()).stream()
                .limit(10)
                .map(SlackLinkedConnectionResponse::getConnectionName)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
            return Optional.of("DeepSQL account linked. Available connections: " + availableConnections + ". Now say `use <connection>`.");
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /**
     * Run one Slack turn through the DeepSQL Agent (slack.brain=agent). Provisions
     * the user's identity-scoped agent profile, reuses a per-thread agent session
     * for context continuity, and returns the assembled answer. Falls back to a
     * fresh session once if the reused one is gone (e.g. agent redeploy).
     */
    private String runAgentTurn(
        String username,
        String connectionId,
        String teamId,
        String channelId,
        String threadTs,
        String message
    ) {
        String profile = agentBridgeService.ensureProfileForUser(username, connectionId);
        String existing = conversationStateService.getAgentSessionId(teamId, channelId, threadTs).orElse(null);
        String sessionId = agentChatClient.ensureSession(profile, existing);
        if (sessionId == null) {
            return "DeepSQL agent is unavailable right now. Please try again shortly.";
        }
        boolean firstTurn = (existing == null);
        if (firstTurn || !sessionId.equals(existing)) {
            conversationStateService.setAgentSessionId(teamId, channelId, threadTs, sessionId);
        }
        String toSend = firstTurn ? withConnectionContext(message, connectionId) : message;
        AgentChatClient.AgentReply reply = agentChatClient.sendAndAwait(sessionId, toSend);

        if (!reply.ok() && !firstTurn) {
            // Reused session may be gone (agent redeployed) — fresh session + retry once.
            String fresh = agentChatClient.ensureSession(profile, null);
            if (fresh != null) {
                conversationStateService.setAgentSessionId(teamId, channelId, threadTs, fresh);
                reply = agentChatClient.sendAndAwait(fresh, withConnectionContext(message, connectionId));
            }
        }
        if (!reply.ok()) {
            return "DeepSQL couldn't complete that request: "
                + (reply.error() == null ? "the agent run ended early." : reply.error());
        }
        String text = reply.text() == null ? "" : reply.text().trim();
        return text.isEmpty() ? "_(the agent finished without a textual answer)_" : text;
    }

    /** Prepend the active connection so the agent grounds on the right DB without a UUID paste. */
    private String withConnectionContext(String message, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return message;
        }
        return "[Active DeepSQL connection: id " + connectionId
            + ". Use this connection unless I name another.]\n\n" + message;
    }

    private boolean isDirectMessage(String channelType) {
        String normalized = channelType == null ? "" : channelType.toLowerCase(Locale.ROOT);
        return "im".equals(normalized) || "dm".equals(normalized);
    }

    private String formatChatResponse(ChatResponse response) {
        if (response == null) {
            return "DeepSQL returned an empty response.";
        }

        StringBuilder builder = new StringBuilder();
        if (response.getMessage() != null && !response.getMessage().isBlank()) {
            builder.append(response.getMessage().trim());
        } else {
            builder.append("DeepSQL completed the request but did not return any message text.");
        }

        if (response.getSql() != null && !response.getSql().isBlank()) {
            builder.append("\n\n```sql\n")
                .append(truncate(response.getSql().trim(), 1800))
                .append("\n```");
        }

        if (response.getConfidence() != null) {
            builder.append("\n\nConfidence: ")
                .append(String.format(Locale.ROOT, "%.2f", response.getConfidence()));
        }

        return truncate(builder.toString(), 3500);
    }

    boolean isSupportedDirectMessage(MessageEvent event) {
        return isSupportedDirectMessage(event, event != null ? event.getChannelType() : null);
    }

    boolean isSupportedDirectMessage(MessageEvent event, String effectiveChannelType) {
        if (event == null) {
            return false;
        }
        if (event.getSubtype() != null && !event.getSubtype().isBlank()) {
            return false;
        }
        if (event.getBotId() != null && !event.getBotId().isBlank()) {
            return false;
        }
        return "im".equalsIgnoreCase(effectiveChannelType)
            || "app_home".equalsIgnoreCase(effectiveChannelType);
    }

    private String stripLeadingMention(String text) {
        String candidate = text == null ? "" : text;
        return LEADING_MENTION_PATTERN.matcher(candidate).replaceFirst("").trim();
    }

    private String normalizeConnectionReference(String rawReference) {
        if (rawReference == null) {
            return "";
        }
        String normalized = rawReference.trim();
        if ((normalized.startsWith("`") && normalized.endsWith("`"))
            || (normalized.startsWith("\"") && normalized.endsWith("\""))
            || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private boolean isKnownConnectionReference(String connectionReference) {
        return conversationStateService.allowedConnections().stream()
            .anyMatch(connection -> connectionReference.equalsIgnoreCase(connection.getId())
                || connectionReference.equalsIgnoreCase(connection.getConnectionName()));
    }

    private String inferChannelType(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return "unknown";
        }
        if (channelId.startsWith("D")) {
            return "im";
        }
        if (channelId.startsWith("G")) {
            return "group";
        }
        return "channel";
    }

    private void postThreadMessage(String channelId, String threadTs, String text) {
        MethodsClient client = methodsClient();
        try {
            client.chatPostMessage(ChatPostMessageRequest.builder()
                .channel(channelId)
                .threadTs(threadTs)
                .text(truncate(text, 3900))
                .build());
        } catch (IOException | SlackApiException e) {
            log.error("Failed to post Slack message", e);
        }
    }

    private MethodsClient methodsClient() {
        return Slack.getInstance().methods(slackRuntimeSettingsService.current().botToken());
    }

    private String resolveBotUserId(SlackRuntimeSettingsService.SlackRuntimeConfig config) {
        try {
            return Slack.getInstance().methods(config.botToken()).authTest(request -> request).getUserId();
        } catch (Exception e) {
            log.warn("Failed to resolve Slack bot user id: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasRequiredConfig(SlackRuntimeSettingsService.SlackRuntimeConfig config) {
        return hasText(config.appToken())
            && hasText(config.botToken())
            && hasText(config.signingSecret());
    }

    private void stopCurrentApp() {
        if (socketModeApp != null) {
            try {
                socketModeApp.close();
            } catch (Exception e) {
                log.debug("Failed to close Slack Socket Mode app cleanly: {}", e.getMessage());
            } finally {
                socketModeApp = null;
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    public record StatusSnapshot(
        boolean started,
        boolean running,
        String lastError,
        String botUserId,
        SlackConversationStateService.StatusSnapshot config
    ) {
    }
}
