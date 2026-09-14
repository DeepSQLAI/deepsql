package com.dbaagent.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaTagTest {

    @Test
    void allPersonaTagsHaveDisplayNameAndDescription() {
        for (PersonaTag tag : PersonaTag.values()) {
            assertThat(tag.getDisplayName()).isNotBlank();
            assertThat(tag.getDescription()).isNotBlank();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "DBA, DBA",
        "dba, DBA",
        "Dba, DBA",
        "APP_ENG, APP_ENG",
        "app_eng, APP_ENG",
        "APP-ENG, APP_ENG",
        "app-eng, APP_ENG",
        "appeng, APP_ENG",
        "app_engineer, APP_ENG",
        "application_engineer, APP_ENG",
        "DATA_ENG, DATA_ENG",
        "data_eng, DATA_ENG",
        "DATA-ENG, DATA_ENG",
        "dataeng, DATA_ENG",
        "data_engineer, DATA_ENG",
        "EXEC, EXEC",
        "exec, EXEC",
        "executive, EXEC"
    })
    void fromString_parsesValidValues(String input, String expected) {
        PersonaTag result = PersonaTag.fromString(input);
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "invalid", "UNKNOWN", "admin", "user"})
    void fromString_returnsNullForInvalidValues(String input) {
        assertThat(PersonaTag.fromString(input)).isNull();
    }

    @Test
    void dbaPersonaTagHasExpectedValues() {
        PersonaTag dba = PersonaTag.DBA;
        assertThat(dba.getDisplayName()).isEqualTo("DBA");
        assertThat(dba.getDescription()).contains("performance");
    }

    @Test
    void execPersonaTagHasExpectedValues() {
        PersonaTag exec = PersonaTag.EXEC;
        assertThat(exec.getDisplayName()).isEqualTo("Executive");
        assertThat(exec.getDescription()).contains("cost");
    }
}
