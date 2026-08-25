package com.dbaagent.service;

import com.dbaagent.model.McpToken;
import com.dbaagent.model.User;
import com.dbaagent.repository.McpTokenRepository;
import com.dbaagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpTokenServiceTest {

    @Mock
    private McpTokenRepository mcpTokenRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private McpTokenService mcpTokenService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setRole("DEVELOPER");
    }

    @Test
    void createTokenBindsTokenToExistingUser() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mcpTokenRepository.existsByPublicId(any())).thenReturn(false);
        when(mcpTokenRepository.save(any(McpToken.class))).thenAnswer(invocation -> {
            McpToken token = invocation.getArgument(0);
            token.setId(42L);
            return token;
        });

        McpTokenService.CreatedToken created = mcpTokenService.createTokenForUser("alice", "Claude Desktop", null);

        assertNotNull(created.plainTextToken());
        assertTrue(created.plainTextToken().startsWith(McpTokenService.TOKEN_PREFIX));
        assertEquals("alice", created.token().getUser().getUsername());
        assertEquals(McpToken.Status.ACTIVE, created.token().getStatus());
        assertTrue(created.token().getTokenPrefix().startsWith(McpTokenService.TOKEN_PREFIX));
    }

    @Test
    void authenticateAcceptsValidTokenAndUpdatesUsage() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mcpTokenRepository.existsByPublicId(any())).thenReturn(false);
        when(mcpTokenRepository.save(any(McpToken.class))).thenAnswer(invocation -> {
            McpToken token = invocation.getArgument(0);
            if (token.getId() == null) {
                token.setId(99L);
            }
            return token;
        });

        McpTokenService.CreatedToken created = mcpTokenService.createTokenForUser("alice", "Codex", LocalDateTime.now().plusDays(7));
        McpToken stored = created.token();
        when(mcpTokenRepository.findByPublicIdAndStatus(stored.getPublicId(), McpToken.Status.ACTIVE))
            .thenReturn(Optional.of(stored));

        Optional<McpTokenService.AuthenticatedMcpToken> authenticated = mcpTokenService.authenticate(
            created.plainTextToken(),
            "127.0.0.1"
        );

        assertTrue(authenticated.isPresent());
        assertEquals(99L, authenticated.get().tokenId());
        assertEquals("alice", authenticated.get().username());
        assertNotNull(stored.getLastUsedAt());
        assertEquals("127.0.0.1", stored.getLastUsedIp());
    }

    @Test
    void authenticateRejectsExpiredTokenAndRevokesIt() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mcpTokenRepository.existsByPublicId(any())).thenReturn(false);
        when(mcpTokenRepository.save(any(McpToken.class))).thenAnswer(invocation -> {
            McpToken token = invocation.getArgument(0);
            if (token.getId() == null) {
                token.setId(101L);
            }
            return token;
        });

        McpTokenService.CreatedToken created = mcpTokenService.createTokenForUser("alice", "Expired Token", LocalDateTime.now().plusDays(1));
        McpToken stored = created.token();
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(5));

        when(mcpTokenRepository.findByPublicIdAndStatus(stored.getPublicId(), McpToken.Status.ACTIVE))
            .thenReturn(Optional.of(stored));

        Optional<McpTokenService.AuthenticatedMcpToken> authenticated = mcpTokenService.authenticate(
            created.plainTextToken(),
            "127.0.0.1"
        );

        assertTrue(authenticated.isEmpty());
        assertEquals(McpToken.Status.REVOKED, stored.getStatus());
        verify(mcpTokenRepository, atLeastOnce()).save(stored);
    }

    @Test
    void authenticateRejectsWrongSecretWithoutUpdatingUsage() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mcpTokenRepository.existsByPublicId(any())).thenReturn(false);
        when(mcpTokenRepository.save(any(McpToken.class))).thenAnswer(invocation -> {
            McpToken token = invocation.getArgument(0);
            if (token.getId() == null) {
                token.setId(102L);
            }
            return token;
        });

        McpTokenService.CreatedToken created = mcpTokenService.createTokenForUser("alice", "Codex", LocalDateTime.now().plusDays(7));
        McpToken stored = created.token();
        when(mcpTokenRepository.findByPublicIdAndStatus(stored.getPublicId(), McpToken.Status.ACTIVE))
            .thenReturn(Optional.of(stored));

        String wrongSecretToken = stored.getTokenPrefix() + ".wrong-secret";
        Optional<McpTokenService.AuthenticatedMcpToken> authenticated = mcpTokenService.authenticate(
            wrongSecretToken,
            "127.0.0.1"
        );

        assertTrue(authenticated.isEmpty());
        assertNull(stored.getLastUsedAt());
        assertNull(stored.getLastUsedIp());
        verify(mcpTokenRepository, times(1)).save(any(McpToken.class));
        verify(mcpTokenRepository).findByPublicIdAndStatus(eq(stored.getPublicId()), eq(McpToken.Status.ACTIVE));
        verify(mcpTokenRepository, never()).findByPublicIdAndStatus(anyString(), eq(McpToken.Status.REVOKED));
    }

    @Test
    void revokeTokenOnlyForOwningUser() {
        McpToken token = new McpToken();
        token.setId(11L);
        token.setUser(user);
        token.setStatus(McpToken.Status.ACTIVE);

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mcpTokenRepository.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(token));

        mcpTokenService.revokeTokenForUser(11L, "alice");

        ArgumentCaptor<McpToken> tokenCaptor = ArgumentCaptor.forClass(McpToken.class);
        verify(mcpTokenRepository).save(tokenCaptor.capture());
        assertEquals(McpToken.Status.REVOKED, tokenCaptor.getValue().getStatus());
    }

    @Test
    void listTokensUsesCurrentUserIdentity() {
        McpToken token = new McpToken();
        token.setId(1L);
        token.setUser(user);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mcpTokenRepository.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(token));

        List<McpToken> tokens = mcpTokenService.listTokensForUser("alice");

        assertEquals(1, tokens.size());
        assertEquals(1L, tokens.get(0).getId());
    }

    @Test
    void isMcpAuthorizationHeaderDetectsBearerPrefix() {
        assertTrue(McpTokenService.isMcpAuthorizationHeader("Bearer dsql_mcp_abc.secret"));
        assertFalse(McpTokenService.isMcpAuthorizationHeader("Bearer eyJhbGciOi"));
        assertFalse(McpTokenService.isMcpAuthorizationHeader("dsql_mcp_abc.secret"));
        assertFalse(McpTokenService.isMcpAuthorizationHeader(null));
        assertFalse(McpTokenService.isMcpAuthorizationHeader(""));
    }
}
