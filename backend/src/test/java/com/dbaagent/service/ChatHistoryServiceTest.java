package com.dbaagent.service;

import com.dbaagent.model.Chat;
import com.dbaagent.model.ChatMessage;
import com.dbaagent.repository.ChatMessageRepository;
import com.dbaagent.repository.ChatRepository;
import com.dbaagent.repository.ChatTurnContextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryServiceTest {

    @Mock private ChatRepository chatRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private ChatTurnContextRepository chatTurnContextRepository;

    private ChatHistoryService chatHistoryService;

    @BeforeEach
    void setUp() {
        chatHistoryService = new ChatHistoryService(chatRepository, messageRepository, chatTurnContextRepository);
        lenient().when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(messageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createChatFromFirstMessage_buildsCompactSummaryTitle() {
        ArgumentCaptor<Chat> chatCaptor = ArgumentCaptor.forClass(Chat.class);

        chatHistoryService.createChatFromFirstMessage(
            "conn-1",
            null,
            "What is the total MRR we have for active properties in India?",
            "alice"
        );

        verify(chatRepository).save(chatCaptor.capture());
        assertThat(chatCaptor.getValue().getTitle())
            .isEqualTo("What is the total MRR we have for active...");
        assertThat(chatCaptor.getValue().getOwnerUsername()).isEqualTo("alice");
    }

    @Test
    void addMessage_autoTitlesPlaceholderChatFromFirstUserMessage() {
        Chat chat = new Chat();
        chat.setId("chat-1");
        chat.setTitle("Active Chat");

        when(chatRepository.findById("chat-1")).thenReturn(Optional.of(chat));
        when(messageRepository.countByChatIdAndRole("chat-1", ChatMessage.MessageRole.USER)).thenReturn(1L);

        chatHistoryService.addMessage("chat-1", ChatMessage.MessageRole.USER, "Show top customers by revenue this month", null);

        assertThat(chat.getTitle()).isEqualTo("Show top customers by revenue this month");
    }

    @Test
    void addMessage_keepsExplicitTitleForEstablishedThread() {
        Chat chat = new Chat();
        chat.setId("chat-1");
        chat.setTitle("Revenue debugging");

        when(chatRepository.findById("chat-1")).thenReturn(Optional.of(chat));

        chatHistoryService.addMessage("chat-1", ChatMessage.MessageRole.USER, "Show top customers by revenue this month", null);

        assertThat(chat.getTitle()).isEqualTo("Revenue debugging");
    }

    @Test
    void getChatsByConnection_filtersOutEmptyPlaceholderChats() {
        Chat emptyChat = new Chat();
        emptyChat.setId("empty-chat");
        emptyChat.setTitle("New chat");

        Chat populatedChat = new Chat();
        populatedChat.setId("populated-chat");
        populatedChat.setTitle("Revenue debugging");

        when(chatRepository.findByConnectionIdAndOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc("conn-1", "alice"))
            .thenReturn(List.of(emptyChat, populatedChat));
        when(messageRepository.countByChatId("empty-chat")).thenReturn(0L);
        when(messageRepository.countByChatId("populated-chat")).thenReturn(2L);

        List<Chat> chats = chatHistoryService.getChatsByConnection("conn-1", "alice");

        assertThat(chats).extracting(Chat::getId).containsExactly("populated-chat");
    }

    @Test
    void getOrCreateActiveChat_isScopedByOwner() {
        Chat existingChat = new Chat();
        existingChat.setId("chat-alice");
        existingChat.setTitle("Revenue debugging");

        when(chatRepository.findByConnectionIdAndOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc("conn-1", "alice"))
            .thenReturn(List.of(existingChat));

        Chat activeChat = chatHistoryService.getOrCreateActiveChat("conn-1", "alice");

        assertThat(activeChat.getId()).isEqualTo("chat-alice");
    }
}
