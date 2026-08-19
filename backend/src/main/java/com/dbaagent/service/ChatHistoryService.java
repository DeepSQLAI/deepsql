package com.dbaagent.service;

import com.dbaagent.model.Chat;
import com.dbaagent.model.ChatHistoryResponse;
import com.dbaagent.model.ChatMessage;
import com.dbaagent.repository.ChatMessageRepository;
import com.dbaagent.repository.ChatRepository;
import com.dbaagent.repository.ChatTurnContextRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryService {
    private static final Set<String> AUTO_TITLE_PLACEHOLDERS = Set.of(
        "active chat",
        "new chat",
        "untitled chat"
    );
    private static final int AUTO_TITLE_MAX_LENGTH = 44;

    private final ChatRepository chatRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatTurnContextRepository chatTurnContextRepository;

    @Transactional
    public Chat createChat(String connectionId, String projectId, String title, String ownerUsername) {
        Chat chat = new Chat();
        chat.setConnectionId(connectionId);
        chat.setProjectId(projectId);
        chat.setOwnerUsername(normalizeOwner(ownerUsername));
        chat.setTitle(title != null ? title : "New chat");

        log.info("Creating chat: {} in project: {} for owner: {}", title, projectId, chat.getOwnerUsername());
        return chatRepository.save(chat);
    }

    @Transactional
    public Chat createChatFromFirstMessage(String connectionId, String projectId, String userMessage, String ownerUsername) {
        return createChat(connectionId, projectId, summarizeTitleFromMessage(userMessage), ownerUsername);
    }

    public List<Chat> getAllChats(String ownerUsername) {
        return filterPersistedChats(ownerScopedChats(ownerUsername, () -> chatRepository.findAllByOrderByLastMessageAtDesc(),
            scopedOwner -> chatRepository.findAllByOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc(scopedOwner)));
    }

    public List<Chat> getChatsByProject(String projectId, String ownerUsername) {
        return filterPersistedChats(ownerScopedChats(ownerUsername, () -> chatRepository.findByProjectIdOrderByLastMessageAtDesc(projectId),
            scopedOwner -> chatRepository.findByProjectIdAndOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc(projectId, scopedOwner)));
    }

    public List<Chat> getChatsByConnection(String connectionId, String ownerUsername) {
        return filterPersistedChats(ownerScopedChats(ownerUsername, () -> chatRepository.findByConnectionIdOrderByLastMessageAtDesc(connectionId),
            scopedOwner -> chatRepository.findByConnectionIdAndOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc(connectionId, scopedOwner)));
    }

    public Optional<Chat> getChat(String chatId, String ownerUsername) {
        return ownerScopedChat(chatId, ownerUsername);
    }

    public ChatHistoryResponse getChatWithHistory(String chatId, String ownerUsername) {
        return ownerScopedChat(chatId, ownerUsername).map(chat -> {
            List<ChatMessage> messages = messageRepository.findByChatIdOrderByCreatedAtAsc(chatId);

            ChatHistoryResponse response = new ChatHistoryResponse();
            response.setId(chat.getId());
            response.setProjectId(chat.getProjectId());
            response.setConnectionId(chat.getConnectionId());
            response.setTitle(chat.getTitle());
            response.setCreatedAt(chat.getCreatedAt());
            response.setUpdatedAt(chat.getUpdatedAt());
            response.setLastMessageAt(chat.getLastMessageAt());

            response.setMessages(messages.stream().map(msg -> {
                ChatHistoryResponse.MessageDTO dto = new ChatHistoryResponse.MessageDTO();
                dto.setId(msg.getId());
                dto.setRole(msg.getRole().name());
                dto.setContent(msg.getContent());
                dto.setSql(msg.getSql());
                dto.setMetadata(msg.getMetadata());
                dto.setCreatedAt(msg.getCreatedAt());
                return dto;
            }).collect(Collectors.toList()));

            return response;
        }).orElse(null);
    }

    @Transactional
    public ChatMessage addMessage(String chatId, ChatMessage.MessageRole role, String content, String sql) {
        return addMessage(chatId, role, content, sql, null);
    }

    @Transactional
    public ChatMessage addMessage(String chatId, ChatMessage.MessageRole role, String content, String sql, String metadata) {
        ChatMessage message = new ChatMessage();
        message.setChatId(chatId);
        message.setRole(role);
        message.setContent(content);
        message.setSql(sql);
        message.setMetadata(metadata);

        messageRepository.save(message);

        // Update chat's last message time
        chatRepository.findById(chatId).ifPresent(chat -> {
            chat.setLastMessageAt(LocalDateTime.now());
            maybeAutoTitleChat(chat, role, content);
            chatRepository.save(chat);
        });

        return message;
    }

    @Transactional
    public Optional<Chat> updateChat(String chatId, String ownerUsername, String title, String projectId) {
        return ownerScopedChat(chatId, ownerUsername).map(chat -> {
            if (title != null) chat.setTitle(title);
            if (projectId != null) chat.setProjectId(projectId);
            log.info("Updating chat: {}", chatId);
            return chatRepository.save(chat);
        });
    }

    @Transactional
    public boolean deleteChat(String chatId, String ownerUsername) {
        if (ownerScopedChat(chatId, ownerUsername).isPresent()) {
            log.info("Deleting chat and its messages: {}", chatId);
            chatTurnContextRepository.deleteByChatId(chatId);
            messageRepository.deleteByChatId(chatId);
            chatRepository.deleteById(chatId);
            return true;
        }
        return false;
    }

    public List<ChatMessage> getChatMessages(String chatId) {
        return messageRepository.findByChatIdOrderByCreatedAtAsc(chatId);
    }

    /**
     * Get or create the active chat for a connection.
     * The active chat is the most recent chat (by lastMessageAt) for the given connection.
     * If no chat exists, creates a new one.
     */
    @Transactional
    public Chat getOrCreateActiveChat(String connectionId, String ownerUsername) {
        List<Chat> chats = ownerScopedChats(
            ownerUsername,
            () -> chatRepository.findByConnectionIdOrderByLastMessageAtDesc(connectionId),
            scopedOwner -> chatRepository.findByConnectionIdAndOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc(connectionId, scopedOwner)
        );

        if (chats.isEmpty()) {
            // Create a new active chat
            Chat newChat = new Chat();
            newChat.setConnectionId(connectionId);
            newChat.setOwnerUsername(normalizeOwner(ownerUsername));
            newChat.setProjectId(null); // No project for single-threaded approach
            newChat.setTitle("New chat");
            log.info("Creating new active chat for connection: {} and owner: {}", connectionId, newChat.getOwnerUsername());
            return chatRepository.save(newChat);
        }
        
        // Return the most recent chat (first in the list)
        Chat activeChat = chats.get(0);
        log.info("Found active chat: {} for connection: {}", activeChat.getId(), connectionId);
        return activeChat;
    }

    /**
     * Clear the active chat by creating a new one.
     * The old chat remains in the database but is no longer the active one.
     */
    @Transactional
    public Chat clearActiveChat(String connectionId, String ownerUsername) {
        Chat newChat = new Chat();
        newChat.setConnectionId(connectionId);
        newChat.setOwnerUsername(normalizeOwner(ownerUsername));
        newChat.setProjectId(null);
        newChat.setTitle("New chat");
        log.info("Clearing active chat for connection: {} and owner: {} - creating new chat", connectionId, newChat.getOwnerUsername());
        return chatRepository.save(newChat);
    }

    private Optional<Chat> ownerScopedChat(String chatId, String ownerUsername) {
        String normalizedOwner = normalizeOwner(ownerUsername);
        if (normalizedOwner == null) {
            return chatRepository.findById(chatId);
        }
        return chatRepository.findByIdAndOwnerUsernameIgnoreCase(chatId, normalizedOwner);
    }

    private List<Chat> ownerScopedChats(
        String ownerUsername,
        java.util.function.Supplier<List<Chat>> unscopedSupplier,
        java.util.function.Function<String, List<Chat>> scopedSupplier
    ) {
        String normalizedOwner = normalizeOwner(ownerUsername);
        if (normalizedOwner == null) {
            return unscopedSupplier.get();
        }
        return scopedSupplier.apply(normalizedOwner);
    }

    private String normalizeOwner(String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            return null;
        }
        return ownerUsername.trim();
    }

    private void maybeAutoTitleChat(Chat chat, ChatMessage.MessageRole role, String content) {
        if (chat == null || role != ChatMessage.MessageRole.USER || content == null || content.isBlank()) {
            return;
        }
        if (!shouldAutoTitle(chat.getTitle())) {
            return;
        }
        long userMessageCount = messageRepository.countByChatIdAndRole(chat.getId(), ChatMessage.MessageRole.USER);
        if (userMessageCount != 1) {
            return;
        }
        chat.setTitle(summarizeTitleFromMessage(content));
    }

    private boolean shouldAutoTitle(String title) {
        if (title == null || title.isBlank()) {
            return true;
        }
        return AUTO_TITLE_PLACEHOLDERS.contains(title.trim().toLowerCase());
    }

    private String summarizeTitleFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return "New chat";
        }

        String normalized = message
            .replaceAll("\\s+", " ")
            .replaceAll("^[\\-*>\\s]+", "")
            .trim();
        if (normalized.isBlank()) {
            return "New chat";
        }

        String punctuationTrimmed = normalized.replaceAll("[\\s?.!,;:]++$", "");
        String candidate = punctuationTrimmed.isBlank() ? normalized : punctuationTrimmed;
        if (candidate.length() <= AUTO_TITLE_MAX_LENGTH) {
            return candidate;
        }

        int boundary = candidate.lastIndexOf(' ', AUTO_TITLE_MAX_LENGTH);
        if (boundary < 18) {
            boundary = AUTO_TITLE_MAX_LENGTH;
        }
        return candidate.substring(0, boundary).trim() + "...";
    }

    private List<Chat> filterPersistedChats(List<Chat> chats) {
        return chats.stream()
            .filter(this::hasPersistedMessages)
            .toList();
    }

    private boolean hasPersistedMessages(Chat chat) {
        return chat != null && messageRepository.countByChatId(chat.getId()) > 0;
    }
}
