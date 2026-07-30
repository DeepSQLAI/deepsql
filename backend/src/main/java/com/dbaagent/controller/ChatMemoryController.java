package com.dbaagent.controller;

import com.dbaagent.service.ChatMemoryService;
import com.dbaagent.service.security.AccessControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing conversation memory.
 *
 * Provides endpoints for:
 * - Viewing conversation history
 * - Listing conversations
 * - Clearing conversation history
 *
 * This controller gracefully handles the case when ChatMemoryService is not available
 * (e.g., when JDBC repository is not configured).
 */
@RestController
@RequestMapping("/memory")
@Slf4j
public class ChatMemoryController {

    private final ChatMemoryService chatMemoryService;
    private final AccessControlService accessControlService;

    public ChatMemoryController(@Nullable ChatMemoryService chatMemoryService, AccessControlService accessControlService) {
        this.chatMemoryService = chatMemoryService;
        this.accessControlService = accessControlService;
        if (chatMemoryService == null) {
            log.warn("ChatMemoryController initialized without ChatMemoryService - JDBC memory not available");
        } else {
            log.info("ChatMemoryController initialized with JDBC chat memory support");
        }
    }

    private boolean isAvailable() {
        return chatMemoryService != null && chatMemoryService.isAvailable();
    }

    /**
     * Check if chat memory service is available.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", isAvailable());
        status.put("message", isAvailable() ? "JDBC chat memory is available" : "JDBC chat memory is not configured");

        if (isAvailable()) {
            status.put("configuredMaxMessages", chatMemoryService.getConfiguredMaxMessages());
            status.put("expectedMaxMessages", chatMemoryService.getExpectedMaxMessages());
            status.put("effectiveMaxMessages", chatMemoryService.getEffectiveMaxMessages());
            status.put("windowMatchesExpected", chatMemoryService.isWindowMatchingExpected());
            status.put("windowVerifiedAt", chatMemoryService.getWindowVerifiedAt());
        }

        return ResponseEntity.ok(status);
    }

    /**
     * Get conversation history for a specific chat session.
     */
    @GetMapping("/connection/{connectionId}/chat/{chatId}")
    public ResponseEntity<Map<String, Object>> getConversationHistory(
            @PathVariable String connectionId,
            @PathVariable String chatId) {
        accessControlService.assertCanUseChatEditor(connectionId);
        accessControlService.assertChatBelongsToConnectionIfPresent(chatId, connectionId);

        if (!isAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "connectionId", connectionId,
                    "chatId", chatId,
                    "messageCount", 0,
                    "messages", Collections.emptyList(),
                    "available", false
            ));
        }

        List<Map<String, String>> messages = chatMemoryService.getHistoryAsMaps(connectionId, chatId);

        return ResponseEntity.ok(Map.of(
                "connectionId", connectionId,
                "chatId", chatId,
                "messageCount", messages.size(),
                "messages", messages,
                "available", true
        ));
    }

    /**
     * List all conversations for a connection.
     */
    @GetMapping("/connection/{connectionId}/conversations")
    public ResponseEntity<Map<String, Object>> listConversations(@PathVariable String connectionId) {
        accessControlService.assertCanUseChatEditor(connectionId);
        String username = accessControlService.requireCurrentUsername();
        if (!isAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "connectionId", connectionId,
                    "conversationCount", 0,
                    "chatIds", Collections.emptyList(),
                    "available", false
            ));
        }

        List<String> chatIds = chatMemoryService.getConversationsForConnection(connectionId, username);

        return ResponseEntity.ok(Map.of(
                "connectionId", connectionId,
                "conversationCount", chatIds.size(),
                "chatIds", chatIds,
                "available", true
        ));
    }

    /**
     * Clear conversation history for a specific chat session.
     */
    @DeleteMapping("/connection/{connectionId}/chat/{chatId}")
    public ResponseEntity<Map<String, Object>> clearConversation(
            @PathVariable String connectionId,
            @PathVariable String chatId) {
        accessControlService.assertCanUseChatEditor(connectionId);
        accessControlService.assertChatBelongsToConnectionIfPresent(chatId, connectionId);

        if (!isAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "connectionId", connectionId,
                    "chatId", chatId,
                    "cleared", false,
                    "available", false
            ));
        }

        chatMemoryService.clearConversation(connectionId, chatId);

        return ResponseEntity.ok(Map.of(
                "connectionId", connectionId,
                "chatId", chatId,
                "cleared", true,
                "available", true
        ));
    }

    /**
     * Delete all conversation history for a connection.
     */
    @DeleteMapping("/connection/{connectionId}")
    public ResponseEntity<Map<String, Object>> deleteAllForConnection(@PathVariable String connectionId) {
        accessControlService.assertCanUseChatEditor(connectionId);
        String username = accessControlService.requireCurrentUsername();
        if (!isAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "connectionId", connectionId,
                    "deletedConversations", 0,
                    "cleared", false,
                    "available", false
            ));
        }

        List<String> chatIds = chatMemoryService.getConversationsForConnection(connectionId, username);
        chatMemoryService.deleteAllForConnection(connectionId, username);

        return ResponseEntity.ok(Map.of(
                "connectionId", connectionId,
                "deletedConversations", chatIds.size(),
                "cleared", true,
                "available", true
        ));
    }

    /**
     * Get message count for a conversation.
     */
    @GetMapping("/connection/{connectionId}/chat/{chatId}/count")
    public ResponseEntity<Map<String, Object>> getMessageCount(
            @PathVariable String connectionId,
            @PathVariable String chatId) {
        accessControlService.assertCanUseChatEditor(connectionId);
        accessControlService.assertChatBelongsToConnectionIfPresent(chatId, connectionId);

        if (!isAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "connectionId", connectionId,
                    "chatId", chatId,
                    "messageCount", 0,
                    "available", false
            ));
        }

        int count = chatMemoryService.getMessageCount(connectionId, chatId);

        return ResponseEntity.ok(Map.of(
                "connectionId", connectionId,
                "chatId", chatId,
                "messageCount", count,
                "available", true
        ));
    }
}
