package com.dbaagent.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DigestDeliveryMethodTest {

    @Test
    void allDeliveryMethodsHaveDisplayNameAndDescription() {
        for (DigestDeliveryMethod method : DigestDeliveryMethod.values()) {
            assertThat(method.getDisplayName()).isNotBlank();
            assertThat(method.getDescription()).isNotBlank();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "SLACK_DM, SLACK_DM",
        "slack_dm, SLACK_DM",
        "SLACKDM, SLACK_DM",
        "slackdm, SLACK_DM",
        "DM, SLACK_DM",
        "dm, SLACK_DM",
        "SLACK_CHANNEL, SLACK_CHANNEL",
        "slack_channel, SLACK_CHANNEL",
        "SLACKCHANNEL, SLACK_CHANNEL",
        "CHANNEL, SLACK_CHANNEL",
        "EMAIL, EMAIL",
        "email, EMAIL",
        "MAIL, EMAIL",
        "mail, EMAIL"
    })
    void fromString_parsesValidValues(String input, String expected) {
        DigestDeliveryMethod result = DigestDeliveryMethod.fromString(input);
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "invalid", "UNKNOWN", "SMS", "webhook"})
    void fromString_returnsNullForInvalidValues(String input) {
        assertThat(DigestDeliveryMethod.fromString(input)).isNull();
    }

    @Test
    void slackDmHasExpectedValues() {
        DigestDeliveryMethod dm = DigestDeliveryMethod.SLACK_DM;
        assertThat(dm.getDisplayName()).isEqualTo("Slack DM");
        assertThat(dm.getDescription()).contains("Slack");
    }

    @Test
    void emailHasExpectedValues() {
        DigestDeliveryMethod email = DigestDeliveryMethod.EMAIL;
        assertThat(email.getDisplayName()).isEqualTo("Email");
        assertThat(email.getDescription()).contains("email");
    }
}
