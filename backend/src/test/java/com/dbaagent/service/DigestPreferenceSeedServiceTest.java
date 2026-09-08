package com.dbaagent.service;

import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.Role;
import com.dbaagent.model.SlackDigestConfig;
import com.dbaagent.model.SlackUserLink;
import com.dbaagent.model.User;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.repository.SlackDigestConfigRepository;
import com.dbaagent.repository.UserDigestPreferenceRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.security.ConnectionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for DigestPreferenceSeedService.
 *
 * <h3>Key scenarios:</h3>
 * <ul>
 *   <li>Seed preferences for Slack-linked users</li>
 *   <li>Infer persona from role</li>
 *   <li>Skip existing preferences (idempotent)</li>
 *   <li>Dry run mode</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DigestPreferenceSeedServiceTest {

    @Mock private UserDigestPreferenceRepository preferenceRepository;
    @Mock private SlackDigestConfigRepository configRepository;
    @Mock private SlackUserLinkService slackUserLinkService;
    @Mock private ConnectionAccessService connectionAccessService;
    @Mock private UserRepository userRepository;

    private DigestPreferenceSeedService seedService;

    @BeforeEach
    void setUp() {
        seedService = new DigestPreferenceSeedService(
            preferenceRepository,
            configRepository,
            slackUserLinkService,
            connectionAccessService,
            userRepository
        );
    }

    @Test
    void seedPreferences_createsPreferenceForLinkedUser() {
        // Given: a linked user with access to a connection
        when(slackUserLinkService.getAllLinkedUsernames()).thenReturn(List.of("alice"));

        User alice = new User();
        alice.setUsername("alice");
        alice.setRole("DBA");
        alice.setAccountStatus("ACTIVE");
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(alice));

        DatabaseConnection conn = new DatabaseConnection();
        conn.setId("conn-123");
        when(connectionAccessService.getVisibleConnections("alice", false))
            .thenReturn(List.of(conn));

        when(preferenceRepository.findByUsernameAndConnectionIdAndDeliveryMethod(
            anyString(), anyString(), any())).thenReturn(Optional.empty());

        when(configRepository.findById(1L)).thenReturn(Optional.of(new SlackDigestConfig()));

        // When: seeding preferences
        DigestPreferenceSeedService.SeedResult result = seedService.executeSeed();

        // Then: one preference is created
        assertThat(result.preferencesCreated()).isEqualTo(1);
        assertThat(result.usersProcessed()).isEqualTo(1);

        ArgumentCaptor<UserDigestPreference> captor = ArgumentCaptor.forClass(UserDigestPreference.class);
        verify(preferenceRepository).save(captor.capture());

        UserDigestPreference saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getConnectionId()).isEqualTo("conn-123");
        assertThat(saved.getDeliveryMethod()).isEqualTo(DigestDeliveryMethod.SLACK_DM);
        assertThat(saved.getPersonaTag()).isEqualTo(PersonaTag.DBA); // Inferred from role
    }

    @Test
    void seedPreferences_infersPersonaFromRole() {
        // Role -> Persona mapping:
        // DBA -> PersonaTag.DBA
        // DATA_ENGINEER -> PersonaTag.DATA_ENG
        // DEVELOPER -> PersonaTag.APP_ENG
        // ADMIN -> null (let them choose)

        when(slackUserLinkService.getAllLinkedUsernames())
            .thenReturn(List.of("dba", "dataeng", "dev", "admin"));

        setupUser("dba", "DBA");
        setupUser("dataeng", "DATA_ENGINEER");
        setupUser("dev", "DEVELOPER");
        setupUser("admin", "ADMIN");

        DatabaseConnection conn = new DatabaseConnection();
        conn.setId("conn-123");
        when(connectionAccessService.getVisibleConnections(anyString(), anyBoolean()))
            .thenReturn(List.of(conn));

        when(preferenceRepository.findByUsernameAndConnectionIdAndDeliveryMethod(
            anyString(), anyString(), any())).thenReturn(Optional.empty());

        when(configRepository.findById(1L)).thenReturn(Optional.of(new SlackDigestConfig()));

        // When: seeding
        DigestPreferenceSeedService.SeedResult result = seedService.executeSeed();

        // Then: correct personas are assigned
        assertThat(result.preferencesCreated()).isEqualTo(4);

        ArgumentCaptor<UserDigestPreference> captor = ArgumentCaptor.forClass(UserDigestPreference.class);
        verify(preferenceRepository, times(4)).save(captor.capture());

        List<UserDigestPreference> saved = captor.getAllValues();

        assertThat(saved.stream()
            .filter(p -> "dba".equals(p.getUsername()))
            .findFirst().get().getPersonaTag()).isEqualTo(PersonaTag.DBA);

        assertThat(saved.stream()
            .filter(p -> "dataeng".equals(p.getUsername()))
            .findFirst().get().getPersonaTag()).isEqualTo(PersonaTag.DATA_ENG);

        assertThat(saved.stream()
            .filter(p -> "dev".equals(p.getUsername()))
            .findFirst().get().getPersonaTag()).isEqualTo(PersonaTag.APP_ENG);

        assertThat(saved.stream()
            .filter(p -> "admin".equals(p.getUsername()))
            .findFirst().get().getPersonaTag()).isNull();
    }

    @Test
    void seedPreferences_skipsExistingPreference() {
        // Given: a user with an existing preference
        when(slackUserLinkService.getAllLinkedUsernames()).thenReturn(List.of("alice"));

        User alice = new User();
        alice.setUsername("alice");
        alice.setRole("DBA");
        alice.setAccountStatus("ACTIVE");
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(alice));

        DatabaseConnection conn = new DatabaseConnection();
        conn.setId("conn-123");
        when(connectionAccessService.getVisibleConnections("alice", false))
            .thenReturn(List.of(conn));

        // Existing preference
        UserDigestPreference existing = UserDigestPreference.builder()
            .username("alice")
            .connectionId("conn-123")
            .build();
        when(preferenceRepository.findByUsernameAndConnectionIdAndDeliveryMethod(
            "alice", "conn-123", DigestDeliveryMethod.SLACK_DM))
            .thenReturn(Optional.of(existing));

        // When: seeding
        DigestPreferenceSeedService.SeedResult result = seedService.executeSeed();

        // Then: skipped, no new preference created
        assertThat(result.preferencesCreated()).isEqualTo(0);
        assertThat(result.skipped()).contains("alice/conn-123: preference exists");
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void seedPreferences_dryRun_doesNotPersist() {
        // Given: a linked user
        when(slackUserLinkService.getAllLinkedUsernames()).thenReturn(List.of("alice"));

        User alice = new User();
        alice.setUsername("alice");
        alice.setRole("DBA");
        alice.setAccountStatus("ACTIVE");
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(alice));

        DatabaseConnection conn = new DatabaseConnection();
        conn.setId("conn-123");
        when(connectionAccessService.getVisibleConnections("alice", false))
            .thenReturn(List.of(conn));

        when(preferenceRepository.findByUsernameAndConnectionIdAndDeliveryMethod(
            anyString(), anyString(), any())).thenReturn(Optional.empty());

        when(configRepository.findById(1L)).thenReturn(Optional.of(new SlackDigestConfig()));

        // When: preview (dry run)
        DigestPreferenceSeedService.SeedResult result = seedService.previewSeed();

        // Then: result shows what would be created, but nothing is persisted
        assertThat(result.preferencesCreated()).isEqualTo(1);
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void seedPreferences_skipsInactiveUser() {
        // Given: an inactive linked user
        when(slackUserLinkService.getAllLinkedUsernames()).thenReturn(List.of("inactive"));

        User inactive = new User();
        inactive.setUsername("inactive");
        inactive.setAccountStatus("DISABLED");
        when(userRepository.findByUsernameIgnoreCase("inactive")).thenReturn(Optional.of(inactive));

        // When: seeding
        DigestPreferenceSeedService.SeedResult result = seedService.executeSeed();

        // Then: skipped
        assertThat(result.preferencesCreated()).isEqualTo(0);
        assertThat(result.skipped()).contains("inactive: user inactive");
    }

    @Test
    void seedPreferences_skipsUserWithNoConnections() {
        // Given: a linked user with no accessible connections
        when(slackUserLinkService.getAllLinkedUsernames()).thenReturn(List.of("noconn"));

        User noconn = new User();
        noconn.setUsername("noconn");
        noconn.setAccountStatus("ACTIVE");
        when(userRepository.findByUsernameIgnoreCase("noconn")).thenReturn(Optional.of(noconn));

        when(connectionAccessService.getVisibleConnections("noconn", false))
            .thenReturn(List.of());

        // When: seeding
        DigestPreferenceSeedService.SeedResult result = seedService.executeSeed();

        // Then: skipped
        assertThat(result.preferencesCreated()).isEqualTo(0);
        assertThat(result.skipped()).contains("noconn: no accessible connections");
    }

    @Test
    void seedForUser_createsPreferencesForSpecificUser() {
        // Given: a specific user with Slack linked
        User alice = new User();
        alice.setUsername("alice");
        alice.setRole("DBA");
        alice.setAccountStatus("ACTIVE");
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(alice));

        SlackUserLink link = new SlackUserLink();
        link.setDeepsqlUsername("alice");
        when(slackUserLinkService.getLinkedSlackAccounts("alice")).thenReturn(List.of(link));

        DatabaseConnection conn1 = new DatabaseConnection();
        conn1.setId("conn-1");
        DatabaseConnection conn2 = new DatabaseConnection();
        conn2.setId("conn-2");
        when(connectionAccessService.getVisibleConnections("alice", false))
            .thenReturn(List.of(conn1, conn2));

        when(preferenceRepository.findByUsernameAndConnectionIdAndDeliveryMethod(
            anyString(), anyString(), any())).thenReturn(Optional.empty());

        // When: seeding for this user
        DigestPreferenceSeedService.SeedResult result = seedService.seedPreferencesForUser("alice", false);

        // Then: preferences created for all connections
        assertThat(result.preferencesCreated()).isEqualTo(2);
        verify(preferenceRepository, times(2)).save(any());
    }

    private void setupUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setRole(role);
        user.setAccountStatus("ACTIVE");
        when(userRepository.findByUsernameIgnoreCase(username)).thenReturn(Optional.of(user));
    }
}
