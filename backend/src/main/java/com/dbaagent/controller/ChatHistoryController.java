package com.dbaagent.controller;

import com.dbaagent.model.Chat;
import com.dbaagent.model.ChatHistoryResponse;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.ChatHistoryService;
import com.dbaagent.service.security.AccessControlService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
public class ChatHistoryController {
    private final ChatHistoryService chatHistoryService;
    private final CredentialService credentialService;
    private final AccessControlService accessControlService;

    @PostMapping
    public ResponseEntity<Chat> createChat(@RequestBody CreateChatRequest request) {
        accessControlService.assertCanUseChatEditor(request.getConnectionId());
        String username = accessControlService.requireCurrentUsername();
        Chat chat = chatHistoryService.createChat(
            request.getConnectionId(),
            request.getProjectId(),
            request.getTitle(),
            username
        );
        return ResponseEntity.ok(chat);
    }

    @GetMapping
    public ResponseEntity<List<Chat>> listChats(
        @RequestParam(required = false) String projectId,
        @RequestParam(required = false) String connectionId
    ) {
        String username = accessControlService.requireCurrentUsername();
        List<Chat> chats;
        if (projectId != null) {
            chats = chatHistoryService.getChatsByProject(projectId, username);
        } else if (connectionId != null) {
            accessControlService.assertCanUseChatEditor(connectionId);
            chats = chatHistoryService.getChatsByConnection(connectionId, username);
        } else {
            chats = chatHistoryService.getAllChats(username);
        }
        chats = filterAccessibleChats(chats);
        return ResponseEntity.ok(chats);
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<ChatHistoryResponse> getChatWithHistory(@PathVariable String chatId) {
        accessControlService.assertCanAccessChat(chatId);
        ChatHistoryResponse response = chatHistoryService.getChatWithHistory(chatId, accessControlService.requireCurrentUsername());
        return response != null
            ? ResponseEntity.ok(response)
            : ResponseEntity.notFound().build();
    }

    @PutMapping("/{chatId}")
    public ResponseEntity<Chat> updateChat(
        @PathVariable String chatId,
        @RequestBody UpdateChatRequest request
    ) {
        accessControlService.assertCanAccessChat(chatId);
        return chatHistoryService.updateChat(chatId, accessControlService.requireCurrentUsername(), request.getTitle(), request.getProjectId())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{chatId}")
    public ResponseEntity<Void> deleteChat(@PathVariable String chatId) {
        accessControlService.assertCanAccessChat(chatId);
        return chatHistoryService.deleteChat(chatId, accessControlService.requireCurrentUsername())
            ? ResponseEntity.ok().build()
            : ResponseEntity.notFound().build();
    }

    @GetMapping("/active")
    public ResponseEntity<ChatHistoryResponse> getActiveChat(
        @RequestParam String connectionId
    ) {
        accessControlService.assertCanUseChatEditor(connectionId);
        String username = accessControlService.requireCurrentUsername();
        Chat activeChat = chatHistoryService.getOrCreateActiveChat(connectionId, username);
        ChatHistoryResponse response = chatHistoryService.getChatWithHistory(activeChat.getId(), username);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/active/clear")
    public ResponseEntity<ChatHistoryResponse> clearActiveChat(
        @RequestParam String connectionId
    ) {
        accessControlService.assertCanUseChatEditor(connectionId);
        String username = accessControlService.requireCurrentUsername();
        Chat newChat = chatHistoryService.clearActiveChat(connectionId, username);
        ChatHistoryResponse response = chatHistoryService.getChatWithHistory(newChat.getId(), username);
        return ResponseEntity.ok(response);
    }

    private List<Chat> filterAccessibleChats(List<Chat> chats) {
        String username = accessControlService.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return List.of();
        }
        boolean admin = accessControlService.isCurrentUserAdmin();
        var allowedConnectionIds = credentialService.getConnectionsForUser(username, admin).stream()
            .map(com.dbaagent.model.DatabaseConnection::getId)
            .collect(java.util.stream.Collectors.toSet());
        return chats.stream()
            .filter(chat -> allowedConnectionIds.contains(chat.getConnectionId()))
            .toList();
    }

    @Data
    public static class CreateChatRequest {
        private String connectionId;
        private String projectId;
        private String title;
    }

    @Data
    public static class UpdateChatRequest {
        private String title;
        private String projectId;
    }
}
