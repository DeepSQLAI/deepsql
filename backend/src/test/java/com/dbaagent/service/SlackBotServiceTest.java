package com.dbaagent.service;

import com.dbaagent.dto.SlackLinkedConnectionResponse;
import com.dbaagent.model.DatabaseConnection;
import com.slack.api.model.event.MessageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackBotServiceTest {

    @Mock
    private SlackRuntimeSettingsService slackRuntimeSettingsService;

    @Mock
    private SlackConversationStateService conversationStateService;

    @Mock
    private SlackUserLinkService slackUserLinkService;

    @Mock
    private ChatService chatService;

    @Test
    void supportsAppHomeDirectMessages() {
        SlackBotService service = new SlackBotService(slackRuntimeSettingsService, conversationStateService, slackUserLinkService, chatService, null, null);
        MessageEvent event = new MessageEvent();
        event.setChannelType("app_home");

        assertTrue(service.isSupportedDirectMessage(event));
    }

    @Test
    void rejectsBotAuthoredDirectMessages() {
        SlackBotService service = new SlackBotService(slackRuntimeSettingsService, conversationStateService, slackUserLinkService, chatService, null, null);
        MessageEvent event = new MessageEvent();
        event.setChannelType("app_home");
        event.setBotId("B123");

        assertFalse(service.isSupportedDirectMessage(event));
    }

    @Test
    void supportsClassicImDirectMessages() {
        SlackBotService service = new SlackBotService(slackRuntimeSettingsService, conversationStateService, slackUserLinkService, chatService, null, null);
        MessageEvent event = new MessageEvent();
        event.setChannelType("im");

        assertTrue(service.isSupportedDirectMessage(event));
    }

    @Test
    void handlesUseCommandInDm() {
        SlackBotService service = new SlackBotService(slackRuntimeSettingsService, conversationStateService, slackUserLinkService, chatService, null, null);
        when(conversationStateService.allowedConnections()).thenReturn(java.util.List.of(connection("conn-1", "aws_sf_prod")));
        when(slackUserLinkService.bindDefaultConnection("T1", "U1", "analyst", false, "aws_sf_prod"))
            .thenReturn(connection("conn-1", "aws_sf_prod"));

        Optional<String> response = service.handlePlainLanguageConnectionCommand(
            "T1",
            "D1",
            "app_home",
            "thread-1",
            "U1",
            new SlackUserLinkService.LinkedUser("analyst", false),
            "use aws_sf_prod"
        );

        assertTrue(response.isPresent());
        assertEquals(
            "DeepSQL is now using `aws_sf_prod` for your Slack session.",
            response.get()
        );
    }

    @Test
    void ignoresNonConnectionUsePhrases() {
        SlackBotService service = new SlackBotService(slackRuntimeSettingsService, conversationStateService, slackUserLinkService, chatService, null, null);
        when(conversationStateService.allowedConnections()).thenReturn(java.util.List.of(connection("conn-1", "aws_sf_prod")));

        Optional<String> response = service.handlePlainLanguageConnectionCommand(
            "T1",
            "D1",
            "app_home",
            "thread-1",
            "U1",
            new SlackUserLinkService.LinkedUser("analyst", false),
            "use these properties"
        );

        assertTrue(response.isEmpty());
    }

    private DatabaseConnection connection(String id, String name) {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId(id);
        connection.setConnectionName(name);
        return connection;
    }
}
