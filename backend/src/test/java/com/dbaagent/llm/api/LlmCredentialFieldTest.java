package com.dbaagent.llm.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the flag combination each named factory produces, since the setup wizard renders
 * its form directly off these flags.
 */
class LlmCredentialFieldTest {

    @Test
    void secretIsSensitiveAndRequiredWithNoPatternOrPlaceholder() {
        LlmCredentialField field = LlmCredentialField.secret("api-key", "API key");

        assertEquals("api-key", field.name());
        assertEquals("API key", field.label());
        assertTrue(field.sensitive());
        assertTrue(field.required());
        assertNull(field.pattern());
        assertNull(field.placeholder());
    }

    @Test
    void requiredIsNotSensitiveButIsRequiredAndCarriesAPlaceholder() {
        LlmCredentialField field = LlmCredentialField.required("endpoint", "Endpoint", "https://example.com");

        assertEquals("endpoint", field.name());
        assertEquals("Endpoint", field.label());
        assertFalse(field.sensitive());
        assertTrue(field.required());
        assertNull(field.pattern());
        assertEquals("https://example.com", field.placeholder());
    }

    @Test
    void optionalIsNeitherSensitiveNorRequiredButCarriesAPlaceholder() {
        LlmCredentialField field = LlmCredentialField.optional("region", "Region", "eastus2");

        assertEquals("region", field.name());
        assertEquals("Region", field.label());
        assertFalse(field.sensitive());
        assertFalse(field.required());
        assertNull(field.pattern());
        assertEquals("eastus2", field.placeholder());
    }
}
