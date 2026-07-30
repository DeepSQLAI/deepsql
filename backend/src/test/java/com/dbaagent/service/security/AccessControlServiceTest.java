package com.dbaagent.service.security;

import com.dbaagent.model.Chat;
import com.dbaagent.model.ChatFeedback;
import com.dbaagent.model.ConnectionOwnershipType;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.EffectiveConnectionAccess;
import com.dbaagent.repository.AnalysisHistoryRepository;
import com.dbaagent.repository.ChatFeedbackRepository;
import com.dbaagent.repository.ChatRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock
    private ConnectionAccessService connectionAccessService;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatFeedbackRepository chatFeedbackRepository;

    @Mock
    private AnalysisHistoryRepository analysisHistoryRepository;

    @InjectMocks
    private AccessControlService accessControlService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(accessControlService, "authEnabled", true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanAccessOwnConnection() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );

        when(connectionAccessService.resolveAccess("conn-1", "alice", false))
            .thenReturn(resolved("conn-1", EffectiveConnectionAccess.OWNER, ConnectionOwnershipType.OWNED));

        assertDoesNotThrow(() -> accessControlService.assertCanAccessConnection("conn-1"));
    }

    @Test
    void nonOwnerIsDenied() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("bob", null, List.of())
        );

        when(connectionAccessService.resolveAccess("conn-1", "bob", false))
            .thenReturn(resolved("conn-1", EffectiveConnectionAccess.NONE, null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accessControlService.assertCanAccessConnection("conn-1"));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void adminCanAccessAnyConnection() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "carol",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            )
        );

        when(connectionAccessService.resolveAccess("conn-1", "carol", true))
            .thenReturn(resolved("conn-1", EffectiveConnectionAccess.ADMIN, ConnectionOwnershipType.ADMIN));

        assertDoesNotThrow(() -> accessControlService.assertCanAccessConnection("conn-1"));
    }

    @Test
    void chatMustBelongToRequestedConnection() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );

        Chat chat = new Chat();
        chat.setId("chat-1");
        chat.setConnectionId("conn-2");
        chat.setOwnerUsername("alice");
        when(chatRepository.findByIdAndOwnerUsernameIgnoreCase("chat-1", "alice")).thenReturn(Optional.of(chat));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accessControlService.assertChatBelongsToConnection("chat-1", "conn-1"));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void userCannotAccessAnotherUsersChatEvenWithConnectionAccess() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );

        when(chatRepository.findByIdAndOwnerUsernameIgnoreCase("chat-1", "alice")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accessControlService.assertCanAccessChat("chat-1"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void feedbackAccessUsesUnderlyingConnectionOwnership() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );

        ChatFeedback feedback = new ChatFeedback();
        feedback.setId("fb-1");
        feedback.setConnectionId("conn-1");
        when(chatFeedbackRepository.findById("fb-1")).thenReturn(Optional.of(feedback));

        when(connectionAccessService.resolveAccess("conn-1", "alice", false))
            .thenReturn(resolved("conn-1", EffectiveConnectionAccess.FULL_CONTENT, ConnectionOwnershipType.ASSIGNED));

        assertDoesNotThrow(() -> accessControlService.assertCanAccessFeedback("fb-1"));
    }

    @Test
    void assignedChatEditorUserCanUseChatEditor() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("dave", null, List.of())
        );

        when(connectionAccessService.resolveAccess("conn-1", "dave", false))
            .thenReturn(resolved("conn-1", EffectiveConnectionAccess.CHAT_EDITOR, ConnectionOwnershipType.ASSIGNED));

        assertDoesNotThrow(() -> accessControlService.assertCanUseChatEditor("conn-1"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accessControlService.assertCanManageConnectionContent("conn-1"));
        assertEquals(403, ex.getStatusCode().value());
    }

    private ConnectionAccessService.ResolvedConnectionAccess resolved(
        String connectionId,
        EffectiveConnectionAccess effectiveAccess,
        ConnectionOwnershipType ownershipType
    ) {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId(connectionId);
        return ConnectionAccessService.ResolvedConnectionAccess.builder()
            .connection(connection)
            .effectiveAccess(effectiveAccess)
            .ownershipType(ownershipType)
            .build();
    }
}
