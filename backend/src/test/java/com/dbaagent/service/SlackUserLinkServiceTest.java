package com.dbaagent.service;

import com.dbaagent.dto.SlackLinkCodeResponse;
import com.dbaagent.model.SlackLinkCode;
import com.dbaagent.model.SlackUserLink;
import com.dbaagent.model.User;
import com.dbaagent.repository.SlackLinkCodeRepository;
import com.dbaagent.repository.SlackThreadSessionRepository;
import com.dbaagent.repository.SlackUserConnectionBindingRepository;
import com.dbaagent.repository.SlackUserLinkRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.security.EncryptionService;
import com.dbaagent.service.security.ConnectionAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackUserLinkServiceTest {

    @Mock
    private SlackLinkCodeRepository slackLinkCodeRepository;
    @Mock
    private SlackUserLinkRepository slackUserLinkRepository;
    @Mock
    private SlackUserConnectionBindingRepository slackUserConnectionBindingRepository;
    @Mock
    private SlackThreadSessionRepository slackThreadSessionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private CredentialService credentialService;
    @Mock
    private ConnectionAccessService connectionAccessService;
    @Mock
    private SecurityEventService securityEventService;

    @InjectMocks
    private SlackUserLinkService service;

    @Test
    void createAndReloadCurrentCodeReturnsPersistentCode() {
        User user = user("alice", false);
        SlackLinkCode stored = new SlackLinkCode();
        stored.setDeepsqlUsername("alice");
        stored.setEncryptedCode("encrypted".getBytes(StandardCharsets.UTF_8));
        stored.setCreatedAt(LocalDateTime.of(2026, 5, 2, 10, 0));

        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(slackLinkCodeRepository.findFirstByDeepsqlUsernameOrderByCreatedAtDesc("alice"))
            .thenReturn(Optional.empty(), Optional.of(stored));
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(encryptionService.encrypt(anyString(), anyString())).thenReturn("encrypted".getBytes(StandardCharsets.UTF_8));
        when(encryptionService.decrypt(any(byte[].class), anyString())).thenReturn("ABCDEFGH");
        when(slackLinkCodeRepository.save(any(SlackLinkCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SlackLinkCodeResponse created = service.createLinkCode("alice");
        SlackLinkCodeResponse current = service.getCurrentLinkCode("alice");

        assertNotNull(created.getCode());
        assertEquals(8, created.getCode().length());
        assertEquals("ABCDEFGH", current.getCode());
        assertEquals(LocalDateTime.of(2026, 5, 2, 10, 0), current.getCreatedAt());
        assertEquals(null, current.getExpiresAt());
    }

    @Test
    void consumeLinkCodeCanBeUsedMultipleTimesWithoutInvalidatingCode() {
        User user = user("alice", false);
        SlackLinkCode code = new SlackLinkCode();
        code.setDeepsqlUsername("alice");
        code.setEncryptedCode("encrypted".getBytes(StandardCharsets.UTF_8));
        code.setCreatedAt(LocalDateTime.now().minusDays(1));

        when(slackLinkCodeRepository.findAll()).thenReturn(List.of(code));
        when(passwordEncoder.matches("ABCDEFGH", code.getCodeHash())).thenReturn(true);
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(slackUserLinkRepository.findByTeamIdAndSlackUserId(anyString(), anyString())).thenReturn(Optional.empty());
        when(slackUserLinkRepository.save(any(SlackUserLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SlackUserLinkService.LinkedUser first = service.consumeLinkCode("T1", "U1", "Alice", "ABCDEFGH");
        SlackUserLinkService.LinkedUser second = service.consumeLinkCode("T1", "U1", "Alice", "ABCDEFGH");

        assertEquals("alice", first.username());
        assertEquals("alice", second.username());

        ArgumentCaptor<SlackLinkCode> codeCaptor = ArgumentCaptor.forClass(SlackLinkCode.class);
        verify(slackLinkCodeRepository, never()).save(codeCaptor.capture());
    }

    @Test
    void createLinkCodeClearsOldConsumedFlagAndPersistsEncryptedCode() {
        User user = user("alice", false);
        SlackLinkCode existing = new SlackLinkCode();
        existing.setDeepsqlUsername("alice");
        existing.setConsumedAt(LocalDateTime.now().minusHours(2));

        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(slackLinkCodeRepository.findFirstByDeepsqlUsernameOrderByCreatedAtDesc("alice")).thenReturn(Optional.of(existing));
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(encryptionService.encrypt(anyString(), anyString())).thenReturn("encrypted".getBytes(StandardCharsets.UTF_8));
        when(slackLinkCodeRepository.save(any(SlackLinkCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createLinkCode("alice");

        ArgumentCaptor<SlackLinkCode> codeCaptor = ArgumentCaptor.forClass(SlackLinkCode.class);
        verify(slackLinkCodeRepository).save(codeCaptor.capture());
        SlackLinkCode saved = codeCaptor.getValue();
        assertFalse(saved.getConsumedAt() != null);
        assertNotNull(saved.getEncryptedCode());
    }

    private User user(String username, boolean admin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        if (admin) {
            user.setRole("ADMIN");
        } else {
            user.setRole("DEVELOPER");
        }
        return user;
    }
}
