package com.dbaagent.llm.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmCredentials} becomes a cache-key component in a later task
 * (via {@link LlmCredentials#signature()}), so its blank-handling and immutability
 * contract are pinned here explicitly.
 */
class LlmCredentialsTest {

    @Test
    void compactConstructorRejectsNullProviderId() {
        assertThrows(NullPointerException.class,
                () -> new LlmCredentials(null, Map.of()));
    }

    @Test
    void compactConstructorDefensivelyCopiesTheValuesMap() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("endpoint", "https://example.test");
        LlmCredentials credentials = new LlmCredentials("openai", mutable);

        mutable.put("endpoint", "https://mutated.test");
        mutable.put("new-key", "new-value");

        assertEquals("https://example.test", credentials.get("endpoint"));
        assertFalse(credentials.has("new-key"));
    }

    @Test
    void getOrDefaultReturnsPresentNonBlankValue() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of("model", "gpt-5.4-pro"));
        assertEquals("gpt-5.4-pro", credentials.getOrDefault("model", "fallback"));
    }

    @Test
    void getOrDefaultFallsBackWhenValueIsMissing() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of());
        assertEquals("fallback", credentials.getOrDefault("model", "fallback"));
    }

    @Test
    void getOrDefaultFallsBackWhenValueIsEmptyString() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of("model", ""));
        assertEquals("fallback", credentials.getOrDefault("model", "fallback"));
    }

    @Test
    void getOrDefaultFallsBackWhenValueIsWhitespaceOnly() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of("model", "   "));
        assertEquals("fallback", credentials.getOrDefault("model", "fallback"));
    }

    @Test
    void hasIsTrueOnlyForPresentNonBlankValue() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of("model", "gpt-5.4-pro"));
        assertTrue(credentials.has("model"));
    }

    @Test
    void hasIsFalseWhenValueIsMissing() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of());
        assertFalse(credentials.has("model"));
    }

    @Test
    void hasIsFalseWhenValueIsEmptyString() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of("model", ""));
        assertFalse(credentials.has("model"));
    }

    @Test
    void hasIsFalseWhenValueIsWhitespaceOnly() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of("model", "  "));
        assertFalse(credentials.has("model"));
    }

    @Test
    void signatureContainsProviderIdAndNonSecretFieldsButNeverASecret() {
        LlmCredentials credentials = new LlmCredentials("azure-openai", Map.of(
                "endpoint", "https://my-resource.cognitiveservices.azure.com/",
                "model", "gpt-5.4-pro",
                "region", "eastus2",
                "api-key", "sk-super-secret-value"
        ));

        String signature = credentials.signature();

        assertTrue(signature.contains("azure-openai"));
        assertTrue(signature.contains("https://my-resource.cognitiveservices.azure.com/"));
        assertTrue(signature.contains("gpt-5.4-pro"));
        assertTrue(signature.contains("eastus2"));
        assertFalse(signature.contains("sk-super-secret-value"));
    }

    @Test
    void signatureToleratesMissingNonSecretFields() {
        LlmCredentials credentials = new LlmCredentials("openai", Map.of());
        assertEquals("openai|||", credentials.signature());
    }
}
