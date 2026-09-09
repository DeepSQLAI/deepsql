package com.dbaagent.service.security;

import com.dbaagent.model.*;
import com.dbaagent.repository.ConnectionAccessGrantRepository;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.ConnectionChatAccessPolicyService;
import com.dbaagent.service.SecurityEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The connection access tiers collapsed into a single level: any grant means full
 * content access.
 *
 * <p>These exercise the <em>real</em> {@link ConnectionAccessService#resolveAccess}
 * path. {@code AccessControlServiceTest} cannot cover this: it stubs
 * {@code resolveAccess} to hand back a fixed {@link EffectiveConnectionAccess}, so it
 * keeps passing no matter what the resolution logic does — it still contains a
 * CHAT_EDITOR case that passes vacuously. A mock cannot catch a change to the thing it
 * replaces.
 */
class ConnectionAccessLevelCollapseTest {

    private static final String CONN = "conn-1";

    private CredentialRepository credentialRepository;
    private ConnectionAccessGrantRepository grantRepository;
    private UserRepository userRepository;
    private ConnectionAccessService service;

    @BeforeEach
    void setUp() {
        credentialRepository = mock(CredentialRepository.class);
        grantRepository = mock(ConnectionAccessGrantRepository.class);
        userRepository = mock(UserRepository.class);

        service = new ConnectionAccessService(
            credentialRepository,
            grantRepository,
            userRepository,
            mock(SecurityEventService.class),
            mock(ConnectionChatAccessPolicyService.class)
        );

        // The connection must be admin-owned to be assignable at all.
        User owner = new User();
        owner.setUsername("admin");
        owner.setRole("ADMIN");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(owner));
    }

    private DatabaseConnection connection() {
        DatabaseConnection c = new DatabaseConnection();
        c.setId(CONN);
        c.setOwnerUsername("admin");
        return c;
    }

    private void grantWith(ConnectionAccessLevel level) {
        ConnectionAccessGrant grant = new ConnectionAccessGrant();
        grant.setConnectionId(CONN);
        grant.setUsername("dave");
        grant.setAccessLevel(level);
        when(grantRepository.findByConnectionIdAndUsernameIgnoreCase(CONN, "dave"))
            .thenReturn(Optional.of(grant));
    }

    @Test
    @DisplayName("A legacy CHAT_EDITOR grant row now resolves to FULL_CONTENT")
    void legacyChatEditorRowIsUpgraded() {
        // Installs predating the collapse still have CHAT_EDITOR rows on disk. They must
        // resolve to full access rather than a tier the UI no longer explains — this is
        // what makes the change work with no DB migration.
        grantWith(ConnectionAccessLevel.CHAT_EDITOR);

        var access = service.resolveAccess(connection(), "dave", false).getEffectiveAccess();

        assertThat(access).isEqualTo(EffectiveConnectionAccess.FULL_CONTENT);
        assertThat(access.canManageContent()).isTrue();
        assertThat(access.canReadContent()).isTrue();
    }

    @Test
    @DisplayName("A FULL_CONTENT grant still resolves to FULL_CONTENT")
    void fullContentUnchanged() {
        grantWith(ConnectionAccessLevel.FULL_CONTENT);

        assertThat(service.resolveAccess(connection(), "dave", false).getEffectiveAccess())
            .isEqualTo(EffectiveConnectionAccess.FULL_CONTENT);
    }

    @Test
    @DisplayName("No grant still means NONE — the collapse must not hand access to strangers")
    void noGrantStillDenied() {
        when(grantRepository.findByConnectionIdAndUsernameIgnoreCase(CONN, "dave"))
            .thenReturn(Optional.empty());

        var access = service.resolveAccess(connection(), "dave", false).getEffectiveAccess();

        assertThat(access).isEqualTo(EffectiveConnectionAccess.NONE);
        assertThat(access.canUseConnection()).isFalse();
        assertThat(access.canManageContent()).isFalse();
    }

    @Test
    @DisplayName("The owner is still OWNER, not merely FULL_CONTENT")
    void ownerUnchanged() {
        assertThat(service.resolveAccess(connection(), "admin", false).getEffectiveAccess())
            .isEqualTo(EffectiveConnectionAccess.OWNER);
    }

    @Test
    @DisplayName("Every stored access-level string parses to FULL_CONTENT")
    void fromStringCollapses() {
        assertThat(ConnectionAccessLevel.fromString("CHAT_EDITOR")).isEqualTo(ConnectionAccessLevel.FULL_CONTENT);
        assertThat(ConnectionAccessLevel.fromString("FULL_CONTENT")).isEqualTo(ConnectionAccessLevel.FULL_CONTENT);
        assertThat(ConnectionAccessLevel.fromString("FULL_ACCESS")).isEqualTo(ConnectionAccessLevel.FULL_CONTENT);
        // Blank is no longer an error: assignment implies full access.
        assertThat(ConnectionAccessLevel.fromString(null)).isEqualTo(ConnectionAccessLevel.FULL_CONTENT);
        assertThat(ConnectionAccessLevel.fromString("  ")).isEqualTo(ConnectionAccessLevel.FULL_CONTENT);
    }
}
