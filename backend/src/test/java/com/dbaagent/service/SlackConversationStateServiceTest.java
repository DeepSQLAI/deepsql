package com.dbaagent.service;

import com.dbaagent.model.Chat;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.SlackChannelBinding;
import com.dbaagent.model.SlackThreadSession;
import com.dbaagent.repository.SlackChannelBindingRepository;
import com.dbaagent.repository.SlackEventReceiptRepository;
import com.dbaagent.repository.SlackThreadSessionRepository;
import com.dbaagent.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackConversationStateServiceTest {

    @Mock
    private SlackRuntimeSettingsService slackRuntimeSettingsService;

    @Mock
    private SlackChannelBindingRepository channelBindingRepository;

    @Mock
    private SlackThreadSessionRepository threadSessionRepository;

    @Mock
    private SlackEventReceiptRepository eventReceiptRepository;

    @Mock
    private CredentialService credentialService;

    @Mock
    private ChatHistoryService chatHistoryService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SlackConversationStateService service;

    @Test
    void bindDefaultConnectionAcceptsOnlySlackOwnedConnections() {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("conn-1");
        connection.setConnectionName("analytics");

        when(slackRuntimeSettingsService.current()).thenReturn(runtimeConfig("slack-bot"));
        when(credentialService.getConnectionsForUser("slack-bot", false)).thenReturn(List.of(connection));
        when(channelBindingRepository.findByTeamIdAndChannelId("T1", "C1")).thenReturn(Optional.empty());
        when(channelBindingRepository.save(any(SlackChannelBinding.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SlackChannelBinding binding = service.bindDefaultConnection("T1", "C1", "channel", "analytics", "U1");

        assertEquals("conn-1", binding.getDefaultConnectionId());
        assertEquals("channel", binding.getChannelType());
    }

    @Test
    void resolveThreadSessionReusesExistingChatForSameSlackThread() {
        SlackChannelBinding binding = new SlackChannelBinding();
        binding.setTeamId("T1");
        binding.setChannelId("C1");
        binding.setChannelType("channel");
        binding.setDefaultConnectionId("conn-new");

        SlackThreadSession existingSession = new SlackThreadSession();
        existingSession.setTeamId("T1");
        existingSession.setChannelId("C1");
        existingSession.setRootThreadTs("123.45");
        existingSession.setConnectionId("conn-original");
        existingSession.setChatId("chat-1");

        when(channelBindingRepository.findByTeamIdAndChannelId("T1", "C1")).thenReturn(Optional.of(binding));
        when(threadSessionRepository.findByTeamIdAndChannelIdAndRootThreadTs("T1", "C1", "123.45"))
            .thenReturn(Optional.of(existingSession));
        when(threadSessionRepository.save(any(SlackThreadSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SlackConversationStateService.ThreadSessionResolution resolution = service.resolveThreadSession(
            "T1",
            "C1",
            "channel",
            "123.45",
            "show me cpu usage"
        );

        assertEquals("conn-original", resolution.connectionId());
        assertEquals("chat-1", resolution.chatId());
        assertEquals("channel", resolution.channelType());
        verify(chatHistoryService, never()).createChatFromFirstMessage(any(), any(), any(), any());
    }

    @Test
    void markEventReceivedRejectsDuplicates() {
        when(eventReceiptRepository.existsById("evt-1")).thenReturn(true);

        assertFalse(service.markEventReceived("evt-1"));
        assertTrue(service.markEventReceived(null));
        verify(eventReceiptRepository, never()).save(any());
    }

    @Test
    void bindConversationResetsThreadToFreshChatWhenConnectionChanges() {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("conn-2");
        connection.setConnectionName("vaultdb");

        SlackThreadSession existingSession = new SlackThreadSession();
        existingSession.setTeamId("T1");
        existingSession.setChannelId("D1");
        existingSession.setRootThreadTs("123.45");
        existingSession.setConnectionId("conn-1");
        existingSession.setChatId("chat-old");

        Chat newChat = new Chat();
        newChat.setId("chat-new");

        when(slackRuntimeSettingsService.current()).thenReturn(runtimeConfig("slack-bot"));
        when(credentialService.getConnectionsForUser("slack-bot", false)).thenReturn(List.of(connection));
        when(channelBindingRepository.findByTeamIdAndChannelId("T1", "D1")).thenReturn(Optional.empty());
        when(channelBindingRepository.save(any(SlackChannelBinding.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(threadSessionRepository.findByTeamIdAndChannelIdAndRootThreadTs("T1", "D1", "123.45"))
            .thenReturn(Optional.of(existingSession));
        when(threadSessionRepository.save(any(SlackThreadSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatHistoryService.createChat("conn-2", null, "New chat", "slack-bot")).thenReturn(newChat);

        SlackConversationStateService.ConnectionBindingResolution resolution = service.bindConversation(
            "T1",
            "D1",
            "app_home",
            "123.45",
            "vaultdb",
            "U1"
        );

        assertEquals("conn-2", resolution.connectionId());
        assertEquals("vaultdb", resolution.connectionName());
        assertEquals("chat-new", resolution.chatId());
        assertTrue(resolution.threadReset());
        assertEquals("conn-2", existingSession.getConnectionId());
        assertEquals("chat-new", existingSession.getChatId());
    }

    private SlackRuntimeSettingsService.SlackRuntimeConfig runtimeConfig(String username) {
        return new SlackRuntimeSettingsService.SlackRuntimeConfig(
            true,
            true,
            "",
            "",
            "",
            username
        );
    }
}
