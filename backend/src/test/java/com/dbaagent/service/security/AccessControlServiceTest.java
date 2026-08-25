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
        com.dbaagent.security.ImpersonationContext.clear();
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

    /**
     * Profile switch has to punch through the auth-disabled admin bypass.
     * Otherwise an admin "viewing as" an editor still sees every connection.
     */
    @Test
    void impersonationDisablesAdminBypassWhileAuthIsOff() {
        ReflectionTestUtils.setField(accessControlService, "authEnabled", false);

        com.dbaagent.model.User impersonator = new com.dbaagent.model.User();
        impersonator.setId(1L);
        impersonator.setUsername("admin");
        impersonator.setRole("ADMIN");
        com.dbaagent.model.User target = new com.dbaagent.model.User();
        target.setId(2L);
        target.setUsername("marts-editor");
        target.setRole("DEVELOPER");
        com.dbaagent.security.ImpersonationContext.enter(
            new com.dbaagent.security.ImpersonationContext.State(impersonator, target)
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("marts-editor", null, List.of())
        );

        when(connectionAccessService.resolveAccess("conn-1", "marts-editor", false))
            .thenReturn(resolved("conn-1", EffectiveConnectionAccess.CHAT_EDITOR, ConnectionOwnershipType.ASSIGNED));

        assertFalse(accessControlService.isCurrentUserAdmin());
        assertEquals("marts-editor", accessControlService.requireCurrentUsername());
        assertDoesNotThrow(() -> accessControlService.assertCanUseChatEditor("conn-1"));
        verify(connectionAccessService).resolveAccess("conn-1", "marts-editor", false);
        verify(connectionAccessService, never()).resolveAccess(eq("conn-1"), eq(null), eq(true));
    }

    /**
     * The dev-mode bypass has to be coherent. Every other check here honours
     * security.auth.enabled, so this one throwing 403 meant turning auth off turned chat
     * off — the opposite of what the flag advertises.
     */
    @Test
    void withAuthDisabledTheCurrentUsernameFallsBackInsteadOfThrowing() {
        ReflectionTestUtils.setField(accessControlService, "authEnabled", false);
        SecurityContextHolder.clearContext();

        assertEquals("admin", accessControlService.requireCurrentUsername());
    }

    /** With auth on — every real deployment — an anonymous caller is still refused. */
    @Test
    void withAuthEnabledAnAnonymousCallerIsStillRefused() {
        SecurityContextHolder.clearContext();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accessControlService.requireCurrentUsername());
        assertEquals(403, ex.getStatusCode().value());
    }

    /** A real principal always wins over the fallback, however the flag is set. */
    @Test
    void aRealPrincipalIsUsedEvenWithAuthDisabled() {
        ReflectionTestUtils.setField(accessControlService, "authEnabled", false);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );

        assertEquals("alice", accessControlService.requireCurrentUsername());
    }

    /**
     * A client generates a chat id when the user opens a conversation and sends it with
     * the first message. Rejecting that as "Chat not found" rejected the opening message
     * of every new conversation.
     */
    @Test
    void aChatIdThatDoesNotExistYetIsAllowedThrough() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );

        when(chatRepository.existsById("brand-new-chat")).thenReturn(false);

        assertDoesNotThrow(() ->
            accessControlService.assertChatBelongsToConnectionIfPresent("brand-new-chat", "conn-1"));
    }

    /**
     * The leniency above must not become a probe. {@code findAccessibleChatIfPresent}
     * matches on id AND owner, so it returns empty for "someone else's chat" exactly as
     * it does for "no such chat" — if the two were collapsed, passing another user's
     * chat id would sail straight through.
     */
    @Test
    void anotherUsersChatIsStillRejectedByTheLenientCheck() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );

        when(chatRepository.existsById("chat-1")).thenReturn(true);
        when(chatRepository.findByIdAndOwnerUsernameIgnoreCase("chat-1", "alice"))
            .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accessControlService.assertChatBelongsToConnectionIfPresent("chat-1", "conn-1"));
        assertEquals(404, ex.getStatusCode().value());
    }

    /** An existing, owned chat still has to match the connection it is used with. */
    @Test
    void anExistingChatStillMustBelongToTheRequestedConnection() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );

        Chat chat = new Chat();
        chat.setId("chat-1");
        chat.setConnectionId("conn-2");
        chat.setOwnerUsername("alice");
        when(chatRepository.existsById("chat-1")).thenReturn(true);
        when(chatRepository.findByIdAndOwnerUsernameIgnoreCase("chat-1", "alice"))
            .thenReturn(Optional.of(chat));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accessControlService.assertChatBelongsToConnectionIfPresent("chat-1", "conn-1"));
        assertEquals(403, ex.getStatusCode().value());
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

    /**
     * Guards the CHAT_EDITOR branch of {@link EffectiveConnectionAccess} itself.
     *
     * <p>Note this state is no longer reachable from a real grant: connection access
     * levels collapsed to a single tier, so {@code ConnectionAccessService.resolveAccess}
     * returns FULL_CONTENT for every grant (see
     * {@code ConnectionAccessLevelCollapseTest}). This test stubs the resolver directly,
     * so it exercises the enum's semantics, not the resolution path — keep it for the
     * former, do not read it as evidence about the latter.
     */
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
