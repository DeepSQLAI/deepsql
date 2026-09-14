package com.dbaagent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserDigestPreferenceTest {

    @Test
    void builder_createsPreferenceWithDefaults() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .build();

        assertThat(pref.getUsername()).isEqualTo("alice");
        assertThat(pref.isEnabled()).isTrue();
        assertThat(pref.getDeliveryMethod()).isEqualTo(DigestDeliveryMethod.SLACK_DM);
        assertThat(pref.getConnectionId()).isNull();
        assertThat(pref.getPersonaTag()).isNull();
        assertThat(pref.getCronExpression()).isNull();
        assertThat(pref.getTimezone()).isNull();
        assertThat(pref.getCreatedAt()).isNotNull();
        assertThat(pref.getUpdatedAt()).isNotNull();
    }

    @Test
    void builder_setsAllFields() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("bob")
            .connectionId("conn-123")
            .enabled(false)
            .personaTag(PersonaTag.DBA)
            .cronExpression("0 0 8 * * *")
            .deliveryMethod(DigestDeliveryMethod.EMAIL)
            .timezone("America/New_York")
            .build();

        assertThat(pref.getUsername()).isEqualTo("bob");
        assertThat(pref.getConnectionId()).isEqualTo("conn-123");
        assertThat(pref.isEnabled()).isFalse();
        assertThat(pref.getPersonaTag()).isEqualTo(PersonaTag.DBA);
        assertThat(pref.getCronExpression()).isEqualTo("0 0 8 * * *");
        assertThat(pref.getDeliveryMethod()).isEqualTo(DigestDeliveryMethod.EMAIL);
        assertThat(pref.getTimezone()).isEqualTo("America/New_York");
    }

    @Test
    void appliesTo_matchesSpecificConnection() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .connectionId("conn-123")
            .build();

        assertThat(pref.appliesTo("conn-123")).isTrue();
        assertThat(pref.appliesTo("conn-456")).isFalse();
        assertThat(pref.appliesTo(null)).isFalse();
    }

    @Test
    void appliesTo_nullConnectionIdAppliesToAll() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .connectionId(null)
            .build();

        assertThat(pref.appliesTo("conn-123")).isTrue();
        assertThat(pref.appliesTo("conn-456")).isTrue();
        assertThat(pref.appliesTo(null)).isTrue();
    }

    @Test
    void getEffectiveCronExpression_returnsCustomWhenSet() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .cronExpression("0 0 8 * * *")
            .build();

        String effective = pref.getEffectiveCronExpression("0 0 9 * * *");
        assertThat(effective).isEqualTo("0 0 8 * * *");
    }

    @Test
    void getEffectiveCronExpression_fallsBackToGlobalWhenNull() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .cronExpression(null)
            .build();

        String effective = pref.getEffectiveCronExpression("0 0 9 * * *");
        assertThat(effective).isEqualTo("0 0 9 * * *");
    }

    @Test
    void getEffectiveCronExpression_fallsBackToGlobalWhenBlank() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .cronExpression("   ")
            .build();

        String effective = pref.getEffectiveCronExpression("0 0 9 * * *");
        assertThat(effective).isEqualTo("0 0 9 * * *");
    }

    @Test
    void isPerUserDelivery_inSlackDigestLog() {
        SlackDigestLog log = new SlackDigestLog();

        assertThat(log.isPerUserDelivery()).isFalse();

        log.setRecipientUsername("alice");
        assertThat(log.isPerUserDelivery()).isTrue();

        log.setRecipientUsername("   ");
        assertThat(log.isPerUserDelivery()).isFalse();
    }

    @Test
    void slackDigestLog_newFieldsHaveDefaults() {
        SlackDigestLog log = new SlackDigestLog();

        assertThat(log.getRecipientUsername()).isNull();
        assertThat(log.getRecipientRole()).isNull();
        assertThat(log.getPersonaTag()).isNull();
        assertThat(log.getDeliveryMethod()).isNull();
        assertThat(log.getPreferenceId()).isNull();
        assertThat(log.isPersonalized()).isFalse();
    }

    @Test
    void slackDigestLog_canSetPerUserFields() {
        SlackDigestLog log = new SlackDigestLog();
        log.setRecipientUsername("alice");
        log.setRecipientRole("ADMIN");
        log.setPersonaTag(PersonaTag.DBA);
        log.setDeliveryMethod(DigestDeliveryMethod.SLACK_DM);
        log.setPreferenceId(42L);
        log.setPersonalized(true);

        assertThat(log.getRecipientUsername()).isEqualTo("alice");
        assertThat(log.getRecipientRole()).isEqualTo("ADMIN");
        assertThat(log.getPersonaTag()).isEqualTo(PersonaTag.DBA);
        assertThat(log.getDeliveryMethod()).isEqualTo(DigestDeliveryMethod.SLACK_DM);
        assertThat(log.getPreferenceId()).isEqualTo(42L);
        assertThat(log.isPersonalized()).isTrue();
        assertThat(log.isPerUserDelivery()).isTrue();
    }
}
