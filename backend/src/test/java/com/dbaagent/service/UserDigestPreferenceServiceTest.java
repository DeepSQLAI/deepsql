package com.dbaagent.service;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.SlackDigestConfig;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.repository.SlackDigestConfigRepository;
import com.dbaagent.repository.UserDigestPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDigestPreferenceServiceTest {

    @Mock
    private UserDigestPreferenceRepository preferenceRepository;

    @Mock
    private SlackDigestConfigRepository configRepository;

    private UserDigestPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new UserDigestPreferenceService(preferenceRepository, configRepository);
    }

    @Test
    void getPreferencesForUser_delegatesToRepository() {
        List<UserDigestPreference> expected = List.of(
            UserDigestPreference.builder().username("alice").build()
        );
        when(preferenceRepository.findByUsernameOrderByConnectionIdAscCreatedAtDesc("alice"))
            .thenReturn(expected);

        List<UserDigestPreference> result = service.getPreferencesForUser("alice");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void hasAnyPreferences_delegatesToRepository() {
        when(preferenceRepository.hasAnyPreferences()).thenReturn(true);
        assertThat(service.hasAnyPreferences()).isTrue();

        when(preferenceRepository.hasAnyPreferences()).thenReturn(false);
        assertThat(service.hasAnyPreferences()).isFalse();
    }

    @Test
    void getGlobalCronExpression_returnsSingletonCron() {
        SlackDigestConfig config = new SlackDigestConfig();
        config.setCronExpression("0 0 10 * * *");
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));

        String result = service.getGlobalCronExpression();

        assertThat(result).isEqualTo("0 0 10 * * *");
    }

    @Test
    void getGlobalCronExpression_returnsDefaultWhenNoConfig() {
        when(configRepository.findById(1L)).thenReturn(Optional.empty());

        String result = service.getGlobalCronExpression();

        assertThat(result).isEqualTo("0 0 9 * * *");
    }

    @Test
    void createPreference_savesNewPreference() {
        when(preferenceRepository.findByUsernameAndConnectionIdAndDeliveryMethod(
            "alice", "conn-1", DigestDeliveryMethod.SLACK_DM))
            .thenReturn(Optional.empty());
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserDigestPreference result = service.createPreference(
            "alice",
            "conn-1",
            DigestDeliveryMethod.SLACK_DM,
            PersonaTag.DBA,
            "0 0 8 * * *",
            "America/New_York"
        );

        assertThat(result.getUsername()).isEqualTo("alice");
        assertThat(result.getConnectionId()).isEqualTo("conn-1");
        assertThat(result.getDeliveryMethod()).isEqualTo(DigestDeliveryMethod.SLACK_DM);
        assertThat(result.getPersonaTag()).isEqualTo(PersonaTag.DBA);
        assertThat(result.getCronExpression()).isEqualTo("0 0 8 * * *");
        assertThat(result.getTimezone()).isEqualTo("America/New_York");
        assertThat(result.isEnabled()).isTrue();

        verify(preferenceRepository).save(any());
    }

    @Test
    void createPreference_throwsWhenDuplicate() {
        when(preferenceRepository.findByUsernameAndConnectionIdAndDeliveryMethod(
            "alice", "conn-1", DigestDeliveryMethod.SLACK_DM))
            .thenReturn(Optional.of(UserDigestPreference.builder().username("alice").build()));

        assertThatThrownBy(() -> service.createPreference(
            "alice", "conn-1", DigestDeliveryMethod.SLACK_DM, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already exists");
    }

    @Test
    void updatePreference_updatesFields() {
        UserDigestPreference existing = UserDigestPreference.builder()
            .id(1L)
            .username("alice")
            .enabled(true)
            .personaTag(PersonaTag.DBA)
            .cronExpression("0 0 8 * * *")
            .build();
        when(preferenceRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserDigestPreference result = service.updatePreference(
            1L, false, PersonaTag.EXEC, "0 0 10 * * *", "Europe/London"
        );

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getPersonaTag()).isEqualTo(PersonaTag.EXEC);
        assertThat(result.getCronExpression()).isEqualTo("0 0 10 * * *");
        assertThat(result.getTimezone()).isEqualTo("Europe/London");
    }

    @Test
    void updatePreference_clearsFieldsWhenBlank() {
        UserDigestPreference existing = UserDigestPreference.builder()
            .id(1L)
            .username("alice")
            .cronExpression("0 0 8 * * *")
            .timezone("America/New_York")
            .build();
        when(preferenceRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserDigestPreference result = service.updatePreference(
            1L, null, null, "", ""
        );

        assertThat(result.getCronExpression()).isNull();
        assertThat(result.getTimezone()).isNull();
    }

    @Test
    void updatePreference_throwsWhenNotFound() {
        when(preferenceRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePreference(999L, true, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void setEnabled_togglesEnableState() {
        UserDigestPreference existing = UserDigestPreference.builder()
            .id(1L)
            .username("alice")
            .enabled(true)
            .build();
        when(preferenceRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserDigestPreference result = service.setEnabled(1L, false);

        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    void deletePreference_delegatesToRepository() {
        service.deletePreference(42L);

        verify(preferenceRepository).deleteById(42L);
    }

    @Test
    void deleteAllPreferencesForUser_delegatesToRepository() {
        service.deleteAllPreferencesForUser("alice");

        verify(preferenceRepository).deleteByUsername("alice");
    }

    @Test
    void countEnabled_delegatesToRepository() {
        when(preferenceRepository.countByEnabledTrue()).thenReturn(5L);

        long result = service.countEnabled();

        assertThat(result).isEqualTo(5L);
    }

    @Test
    void resolveEffectiveCron_usesPreferenceCronWhenSet() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .cronExpression("0 0 8 * * *")
            .build();

        String result = service.resolveEffectiveCron(pref);

        assertThat(result).isEqualTo("0 0 8 * * *");
    }

    @Test
    void resolveEffectiveCron_fallsBackToGlobalWhenNull() {
        SlackDigestConfig config = new SlackDigestConfig();
        config.setCronExpression("0 0 10 * * *");
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));

        UserDigestPreference pref = UserDigestPreference.builder()
            .cronExpression(null)
            .build();

        String result = service.resolveEffectiveCron(pref);

        assertThat(result).isEqualTo("0 0 10 * * *");
    }

    @Test
    void getEnabledPreferences_findsUserAndConnectionSpecificPrefs() {
        List<UserDigestPreference> expected = List.of(
            UserDigestPreference.builder().username("alice").connectionId("conn-1").build(),
            UserDigestPreference.builder().username("alice").connectionId(null).build()
        );
        when(preferenceRepository.findEnabledForUserAndConnection("alice", "conn-1"))
            .thenReturn(expected);

        List<UserDigestPreference> result = service.getEnabledPreferences("alice", "conn-1");

        assertThat(result).hasSize(2);
    }

    @Test
    void getRecipientsForConnection_findsAllEnabledForConnection() {
        List<UserDigestPreference> expected = List.of(
            UserDigestPreference.builder().username("alice").connectionId("conn-1").build(),
            UserDigestPreference.builder().username("bob").connectionId(null).build()
        );
        when(preferenceRepository.findEnabledForConnection("conn-1")).thenReturn(expected);

        List<UserDigestPreference> result = service.getRecipientsForConnection("conn-1");

        assertThat(result).hasSize(2);
    }
}
