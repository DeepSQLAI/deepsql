package com.dbaagent.service;

import com.dbaagent.model.Chat;
import com.dbaagent.repository.ChatRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing conversation memory using Spring AI's ChatMemory.
 *
 * Provides:
 * - Conversation history storage in JDBC/PostgreSQL
 * - Cross-session persistence
 * - Message retrieval for chat context
 * - Startup verification that the effective memory window matches configuration
 *
 * This service gracefully degrades if Spring AI ChatMemory beans are not
 * available (e.g., if JDBC repository is not configured). Check isAvailable() before use.
 */
@Service
@Slf4j
public class ChatMemoryService {

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatRepository chatRepository;
    private final int configuredMaxMessages;
    private final int expectedMaxMessages;
    private final boolean verifyWindowOnStartup;
    private final boolean failFastOnWindowMismatch;

    private volatile Integer effectiveMaxMessages;
    private volatile Boolean windowMatchesExpected;
    private volatile LocalDateTime windowVerifiedAt;

    public ChatMemoryService(
            @Nullable ChatMemory chatMemory,
            @Nullable ChatMemoryRepository chatMemoryRepository,
            @Nullable ChatRepository chatRepository,
            @Value("${app.chat.memory.max-messages:50}") int configuredMaxMessages,
            @Value("${app.chat.memory.expected-max-messages:${app.chat.memory.max-messages:50}}") int expectedMaxMessages,
            @Value("${app.chat.memory.verify-window-on-startup:true}") boolean verifyWindowOnStartup,
            @Value("${app.chat.memory.fail-fast-on-window-mismatch:false}") boolean failFastOnWindowMismatch) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatRepository = chatRepository;
        this.configuredMaxMessages = configuredMaxMessages;
        this.expectedMaxMessages = expectedMaxMessages;
        this.verifyWindowOnStartup = verifyWindowOnStartup;
        this.failFastOnWindowMismatch = failFastOnWindowMismatch;

        if (chatMemory != null && chatMemoryRepository != null) {
            log.info("ChatMemoryService initialized with Spring AI ChatMemory backed by JDBC/PostgreSQL");
        } else {
            log.warn("ChatMemoryService initialized without Spring AI ChatMemory - JDBC chat memory unavailable");
        }
    }

    @PostConstruct
    public void verifyWindowConfiguration() {
        if (!isAvailable()) {
            return;
        }

        if (!verifyWindowOnStartup) {
            log.info("Chat memory window verification skipped (disabled). configuredMaxMessages={}, expectedMaxMessages={}",
                configuredMaxMessages, expectedMaxMessages);
            return;
        }

        Integer effective = probeEffectiveWindow(expectedMaxMessages + 5);
        this.effectiveMaxMessages = effective;
        this.windowVerifiedAt = LocalDateTime.now();
        this.windowMatchesExpected = effective != null && effective == expectedMaxMessages;

        if (Boolean.TRUE.equals(windowMatchesExpected)) {
            log.info("Chat memory window verified. configuredMaxMessages={}, expectedMaxMessages={}, effectiveMaxMessages={}",
                configuredMaxMessages, expectedMaxMessages, effective);
            return;
        }

        log.error("Chat memory window mismatch detected. configuredMaxMessages={}, expectedMaxMessages={}, effectiveMaxMessages={}",
            configuredMaxMessages, expectedMaxMessages, effective);
        if (failFastOnWindowMismatch) {
            throw new IllegalStateException(String.format(
                "Chat memory window mismatch. expected=%d effective=%s",
                expectedMaxMessages, String.valueOf(effective)));
        }
    }

    private Integer probeEffectiveWindow(int probeMessageCount) {
        // Use an isolated synthetic conversation so we can measure the repository-backed
        // message window without affecting real chat history.
        String probeConversationId = UUID.randomUUID().toString();
        try {
            for (int i = 0; i < probeMessageCount; i++) {
                chatMemory.add(probeConversationId, new UserMessage("window-probe-" + i));
            }
            return chatMemory.get(probeConversationId).size();
        } catch (Exception e) {
            log.warn("Failed to probe chat memory window size: {}", e.getMessage());
            return null;
        } finally {
            try {
                chatMemory.clear(probeConversationId);
            } catch (Exception clearError) {
                log.debug("Failed to clear probe conversation {}", probeConversationId, clearError);
            }
        }
    }

    /**
     * Check if the chat memory service is available.
     */
    public boolean isAvailable() {
        return chatMemory != null && chatMemoryRepository != null;
    }

    public int getConfiguredMaxMessages() {
        return configuredMaxMessages;
    }

    public int getExpectedMaxMessages() {
        return expectedMaxMessages;
    }

    public Integer getEffectiveMaxMessages() {
        return effectiveMaxMessages;
    }

    public Boolean isWindowMatchingExpected() {
        return windowMatchesExpected;
    }

    public LocalDateTime getWindowVerifiedAt() {
        return windowVerifiedAt;
    }

    /**
     * Build the Spring AI conversation ID used by JDBC ChatMemory.
     *
     * We intentionally map directly to `chatId` so memory rows align 1:1 with
     * canonical chat history rows (both use the same UUID).
     */
    private String buildConversationId(String connectionId, String chatId) {
        // Use chatId only - it's a UUID and globally unique
        return chatId;
    }

    /**
     * Add a user message to the conversation history.
     *
     * @param connectionId the database connection ID
     * @param chatId       the chat session ID
     * @param content      the user's message content
     */
    public void addUserMessage(String connectionId, String chatId, String content) {
        if (!isAvailable()) {
            log.debug("ChatMemory not available, skipping user message storage");
            return;
        }
        String conversationId = buildConversationId(connectionId, chatId);
        UserMessage message = new UserMessage(content);
        chatMemory.add(conversationId, message);
        log.debug("Added user message to conversation {}", conversationId);
    }

    /**
     * Add an assistant (AI) message to the conversation history.
     *
     * @param connectionId the database connection ID
     * @param chatId       the chat session ID
     * @param content      the AI's response content
     */
    public void addAssistantMessage(String connectionId, String chatId, String content) {
        if (!isAvailable()) {
            log.debug("ChatMemory not available, skipping assistant message storage");
            return;
        }
        String conversationId = buildConversationId(connectionId, chatId);
        AssistantMessage message = new AssistantMessage(content);
        chatMemory.add(conversationId, message);
        log.debug("Added assistant message to conversation {}", conversationId);
    }

    /**
     * Get the conversation history for a chat session.
     *
     * @param connectionId the database connection ID
     * @param chatId       the chat session ID
     * @return list of messages in the conversation
     */
    public List<Message> getConversationHistory(String connectionId, String chatId) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        String conversationId = buildConversationId(connectionId, chatId);
        return chatMemory.get(conversationId);
    }

    /**
     * Get conversation history formatted as a string for inclusion in prompts.
     *
     * @param connectionId the database connection ID
     * @param chatId       the chat session ID
     * @param maxMessages  maximum number of recent messages to include (applied after retrieval)
     * @return formatted conversation history string
     */
    public String getFormattedHistory(String connectionId, String chatId, int maxMessages) {
        if (!isAvailable()) {
            return "";
        }
        String conversationId = buildConversationId(connectionId, chatId);
        List<Message> allMessages = chatMemory.get(conversationId);

        if (allMessages.isEmpty()) {
            return "";
        }

        // Apply maxMessages limit if needed
        List<Message> messages = allMessages;
        if (maxMessages > 0 && allMessages.size() > maxMessages) {
            messages = allMessages.subList(allMessages.size() - maxMessages, allMessages.size());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== CONVERSATION HISTORY ===\n");

        for (Message message : messages) {
            String role = switch (message.getMessageType()) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case SYSTEM -> "System";
                default -> "Unknown";
            };
            sb.append(role).append(": ").append(message.getText()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Clear the conversation history for a chat session.
     *
     * @param connectionId the database connection ID
     * @param chatId       the chat session ID
     */
    public void clearConversation(String connectionId, String chatId) {
        if (!isAvailable()) {
            log.debug("ChatMemory not available, nothing to clear");
            return;
        }
        String conversationId = buildConversationId(connectionId, chatId);
        chatMemory.clear(conversationId);
        log.info("Cleared conversation history for {}", conversationId);
    }

    /**
     * Get conversation IDs for a connection.
     *
     * JDBC memory rows are keyed by chatId only, so we intersect:
     * - conversation IDs present in ChatMemoryRepository
     * - chats owned by the target connection in ChatRepository
     *
     * @param connectionId the database connection ID
     * @return list of chat IDs with memory for this connection
     */
    public List<String> getConversationsForConnection(String connectionId, String ownerUsername) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        List<String> allConversationIds = chatMemoryRepository.findConversationIds();
        if (allConversationIds.isEmpty()) {
            return Collections.emptyList();
        }

        if (connectionId == null || connectionId.isBlank() || chatRepository == null) {
            return allConversationIds;
        }

        Set<String> memoryConversationIds = new HashSet<>(allConversationIds);
        List<Chat> connectionChats = ownerUsername == null || ownerUsername.isBlank()
            ? chatRepository.findByConnectionIdOrderByLastMessageAtDesc(connectionId)
            : chatRepository.findByConnectionIdAndOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc(connectionId, ownerUsername);

        return connectionChats.stream()
            .map(Chat::getId)
            .filter(memoryConversationIds::contains)
            .collect(Collectors.toList());
    }

    /**
     * Delete all conversation history for a connection.
     *
     * @param connectionId the database connection ID
     */
    public void deleteAllForConnection(String connectionId, String ownerUsername) {
        if (!isAvailable()) {
            log.debug("ChatMemory not available, nothing to delete");
            return;
        }
        List<String> chatIds = getConversationsForConnection(connectionId, ownerUsername);

        for (String chatId : chatIds) {
            clearConversation(connectionId, chatId);
        }

        log.info("Deleted {} conversations for connection {}", chatIds.size(), connectionId);
    }

    /**
     * Get message count for a conversation.
     *
     * @param connectionId the database connection ID
     * @param chatId       the chat session ID
     * @return number of messages in the conversation
     */
    public int getMessageCount(String connectionId, String chatId) {
        if (!isAvailable()) {
            return 0;
        }
        String conversationId = buildConversationId(connectionId, chatId);
        return chatMemory.get(conversationId).size();
    }

    /**
     * Convert conversation history to a list of maps for API responses.
     *
     * @param connectionId the database connection ID
     * @param chatId       the chat session ID
     * @return list of message maps with type and content
     */
    public List<Map<String, String>> getHistoryAsMaps(String connectionId, String chatId) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        List<Message> messages = getConversationHistory(connectionId, chatId);

        List<Map<String, String>> result = new ArrayList<>();
        for (Message message : messages) {
            result.add(Map.of(
                    "type", message.getMessageType().name(),
                    "content", message.getText()
            ));
        }

        return result;
    }
}
